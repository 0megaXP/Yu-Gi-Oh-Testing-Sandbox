package com.ygosandbox.app.data

import com.ygosandbox.app.model.CardStat
import com.ygosandbox.app.model.DeckAnalysis
import com.ygosandbox.app.model.GameFormat
import com.ygosandbox.app.model.SourceDeck
import com.ygosandbox.app.model.TimeSpan
import com.ygosandbox.app.model.Usage
import com.ygosandbox.app.net.Http
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** A deck as listed by YGOPRODeck. */
private class RemoteDeck(
    val name: String,
    val date: LocalDate?,
    val tournament: String,
    val placement: String,
    val url: String,
    val formatText: String,
    val main: List<Long>,
    val extra: List<Long>,
    val side: List<Long>,
)

/**
 * Looks up tournament decks on YGOPRODeck that play all the chosen archetypes, then works out
 * which cards those decks have in common and where they play them.
 */
class DeckSearch(private val db: CardDatabase) {

    class NoDecksException(message: String) : Exception(message)

    suspend fun run(
        archetypes: List<String>,
        excluded: List<String>,
        format: GameFormat,
        span: TimeSpan,
        banlists: List<LocalDate>,
        onProgress: (String) -> Unit,
    ): DeckAnalysis {
        val today = LocalDate.now()
        val latestList = banlists.lastOrNull()
        val since: LocalDate? = when (span) {
            TimeSpan.RECENT -> {
                val twoWeeks = today.minusDays(14)
                // If a new list came out in the last two weeks, only count decks from this format.
                if (latestList != null && latestList.isAfter(twoWeeks)) latestList else twoWeeks
            }
            TimeSpan.THIS_BANLIST -> latestList ?: today.minusDays(90)
            TimeSpan.ALL_TIME -> null
        }
        val maxScan = if (span == TimeSpan.ALL_TIME) 3000 else 2000

        // ---- 1. Collect decks ----
        val matched = ArrayList<RemoteDeck>()
        var scanned = 0
        var category = if (format == GameFormat.OCG) CATEGORY_OCG else CATEGORY_TCG
        var filterOcgByText = false
        var offset = 0
        var oldPagesInARow = 0
        while (scanned < maxScan) {
            onProgress("Reading YGOPRODeck decks… $scanned checked, ${matched.size} match")
            val page = fetchPage(category, offset)
            if (page.isEmpty()) {
                // Fall back to the main list if YGOPRODeck has no separate OCG category.
                if (offset == 0 && format == GameFormat.OCG && category == CATEGORY_OCG) {
                    category = CATEGORY_TCG
                    filterOcgByText = true
                    continue
                }
                break
            }
            offset += page.size
            scanned += page.size
            var anyInRange = false
            for (d in page) {
                if (since != null && d.date != null && d.date.isBefore(since)) continue
                anyInRange = true
                if (!formatMatches(d, format, filterOcgByText)) continue
                if (playsAll(d, archetypes) && !playsAny(d, excluded, archetypes)) matched += d
            }
            oldPagesInARow = if (anyInRange) 0 else oldPagesInARow + 1
            // Decks come newest first; two pages in a row that are entirely too old means we're done.
            if (oldPagesInARow >= 2) break
            delay(120) // stay well within YGOPRODeck's rate limit
        }
        if (matched.isEmpty()) {
            throw NoDecksException(
                "Checked $scanned ${format.label} tournament decks" +
                    (since?.let { " since ${Banlists.pretty(it)}" } ?: "") +
                    ", but none play ${archetypes.joinToString(" + ")}" +
                    (if (excluded.isNotEmpty()) " without ${excluded.joinToString(" / ")}" else "") +
                    ". Try a longer time span.",
            )
        }

        // ---- 2. Count cards ----
        onProgress("Analysing ${matched.size} decks…")
        class Tally { var mainDecks = 0; var sideDecks = 0; var bothDecks = 0; var anyDecks = 0; var mainCopies = 0; var sideCopies = 0 }
        val tallies = LinkedHashMap<Long, Tally>()
        val extraIds = HashSet<Long>()
        for (d in matched) {
            val main = (d.main + d.extra).map(::canonical).groupingBy { it }.eachCount()
            val side = d.side.map(::canonical).groupingBy { it }.eachCount()
            d.extra.forEach { extraIds += canonical(it) }
            for (id in main.keys + side.keys) {
                val t = tallies.getOrPut(id) { Tally() }
                t.anyDecks++
                main[id]?.let { t.mainDecks++; t.mainCopies += it }
                side[id]?.let { t.sideDecks++; t.sideCopies += it }
                if (id in main && id in side) t.bothDecks++
            }
        }
        val n = matched.size.toFloat()
        val stats = tallies.map { (id, t) ->
            val card = db.find(id)
            val anyRate = t.anyDecks / n
            // Main and Side are judged on their own: a card in the Main of 90% of the decks that play
            // it is Main, even if many of those decks also side extra copies.
            val inMain = t.mainDecks.toFloat() / t.anyDecks
            val inSide = t.sideDecks.toFloat() / t.anyDecks
            val usage = when {
                anyRate < NICHE_BELOW -> Usage.NICHE
                inMain >= MAIN_SHARE -> Usage.MAIN
                inSide >= MAIN_SHARE -> Usage.SIDE
                else -> Usage.MIX
            }
            CardStat(
                cardId = id,
                name = card?.name ?: "Unknown card #$id",
                engine = card != null && archetypes.any { db.isInArchetype(card, it) },
                usage = usage,
                mainRate = t.mainDecks / n,
                sideRate = t.sideDecks / n,
                bothRate = t.bothDecks / n,
                anyRate = anyRate,
                avgMain = if (t.mainDecks > 0) t.mainCopies.toFloat() / t.mainDecks else 0f,
                avgSide = if (t.sideDecks > 0) t.sideCopies.toFloat() / t.sideDecks else 0f,
                extraDeck = card?.isExtraDeck ?: (id in extraIds),
            )
        }.sortedWith(compareByDescending<CardStat> { it.anyRate }.thenBy { it.name })

        val sources = matched.sortedByDescending { it.date ?: LocalDate.MIN }.map { d ->
            val (label, current) = Banlists.periodOf(d.date, banlists)
            SourceDeck(
                name = d.name, date = d.date?.toString() ?: "", tournament = d.tournament,
                placement = d.placement, url = d.url, formatLabel = label, currentFormat = current,
                main = d.main, extra = d.extra, side = d.side,
            )
        }

        return DeckAnalysis(
            id = UUID.randomUUID().toString(),
            archetypes = archetypes,
            excluded = excluded,
            format = format,
            span = span,
            createdAt = System.currentTimeMillis(),
            since = since?.toString() ?: "",
            decksScanned = scanned,
            cards = stats,
            decks = sources,
        )
    }

    /**
     * True when the deck plays at least [MIN_COPIES] cards of any ignored archetype. Cards that also
     * belong to a wanted archetype don't count, so a shared card can't knock out every deck.
     */
    private fun playsAny(d: RemoteDeck, excluded: List<String>, wanted: List<String>): Boolean {
        if (excluded.isEmpty()) return false
        val cards = (d.main + d.extra).mapNotNull { db.find(it) }
            .filter { c -> wanted.none { db.isDirectlyInArchetype(c, it) } }
        return excluded.any { a -> cards.count { db.isDirectlyInArchetype(it, a) } >= MIN_COPIES }
    }

    private fun canonical(id: Long): Long = db.find(id)?.id ?: id

    /**
     * True when the deck has at least [MIN_COPIES] cards that directly belong to every chosen
     * archetype in Main + Extra. Cards that only join through a cited card (Ecclesia via Albaz)
     * count as engine in the results, but not here.
     */
    private fun playsAll(d: RemoteDeck, archetypes: List<String>): Boolean {
        val cards = (d.main + d.extra).mapNotNull { db.find(it) }
        return archetypes.all { a -> cards.count { db.isDirectlyInArchetype(it, a) } >= MIN_COPIES }
    }

    private fun formatMatches(d: RemoteDeck, format: GameFormat, ocgByText: Boolean): Boolean {
        val text = d.formatText.lowercase(Locale.ROOT)
        val otherGame = listOf("master duel", "rush", "duel links", "goat", "edison", "genesys", "speed duel")
            .any { it in text }
        if (otherGame) return false
        return when (format) {
            GameFormat.TCG -> "ocg" !in text
            GameFormat.OCG -> !ocgByText || "ocg" in text
        }
    }

    // ---------- YGOPRODeck deck API ----------

    private suspend fun fetchPage(category: String, offset: Int): List<RemoteDeck> {
        val url = "$DECK_API?_sft_category=${Http.enc(category)}&offset=$offset&limit=$PAGE"
        val body = Http.get(url).trim()
        if (body.isEmpty() || body == "null") return emptyList()
        val arr: JSONArray = when {
            body.startsWith("[") -> JSONArray(body)
            body.startsWith("{") -> {
                val o = JSONObject(body)
                o.optJSONArray("data") ?: o.optJSONArray("decks") ?: JSONArray()
            }
            else -> throw Exception("YGOPRODeck sent an unexpected answer: ${body.take(120)}")
        }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parseDeck) }
    }

    private fun parseDeck(o: JSONObject): RemoteDeck {
        val pretty = o.optString("pretty_url")
        return RemoteDeck(
            name = o.optString("deck_name").ifBlank { "Deck #${o.optString("deckNum")}" },
            date = parseDate(o.optString("submit_date")) ?: parseDate(o.optString("tournamentDate")),
            tournament = o.optString("tournamentName").takeUnless { it == "null" } ?: "",
            placement = o.optString("tournamentPlacement").takeUnless { it == "null" } ?: "",
            url = if (pretty.isNotBlank()) "https://ygoprodeck.com/deck/$pretty" else "",
            formatText = listOf("format", "deck_name", "tournamentName", "description")
                .joinToString(" ") { o.optString(it) },
            main = ids(o.opt("main_deck")),
            extra = ids(o.opt("extra_deck")),
            side = ids(o.opt("side_deck")),
        )
    }

    /** Card lists come as a JSON array or as a string holding one; either way, pull out the passcodes. */
    private fun ids(value: Any?): List<Long> {
        if (value == null || value == JSONObject.NULL) return emptyList()
        return ID_PATTERN.findAll(value.toString()).mapNotNull { it.value.toLongOrNull() }.toList()
    }

    companion object {
        private const val DECK_API = "https://ygoprodeck.com/api/decks/getDecks.php"
        private const val CATEGORY_TCG = "Tournament Meta Decks"
        private const val CATEGORY_OCG = "Tournament Meta Decks OCG"
        private const val PAGE = 20

        /** A deck must run at least this many cards of each archetype to count. */
        const val MIN_COPIES = 2
        /** Played in fewer than this share of decks → Niche. */
        const val NICHE_BELOW = 0.25f
        /** Main (or Side) when at least this share of the decks that play it have it there. */
        const val MAIN_SHARE = 0.8f

        private val ID_PATTERN = Regex("\\d{3,10}")

        private val FORMATS = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        )
        private val RELATIVE = Regex("(\\d+|an?|one)\\s+(second|minute|hour|day|week|month|year)s?\\s+ago", RegexOption.IGNORE_CASE)

        fun parseDate(raw: String?): LocalDate? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty() || s == "null") return null
            val today = LocalDate.now()
            when (s.lowercase(Locale.ROOT)) {
                "today", "just now" -> return today
                "yesterday" -> return today.minusDays(1)
            }
            RELATIVE.find(s)?.let { m ->
                val n = m.groupValues[1].toLongOrNull() ?: 1L
                return when (m.groupValues[2].lowercase(Locale.ROOT)) {
                    "second", "minute", "hour" -> today
                    "day" -> today.minusDays(n)
                    "week" -> today.minusWeeks(n)
                    "month" -> today.minusMonths(n)
                    else -> today.minusYears(n)
                }
            }
            if (s.length >= 10 && s[4] == '-') runCatching { return LocalDate.parse(s.substring(0, 10)) }
            for (f in FORMATS) {
                runCatching { return LocalDate.parse(s, f) }
                runCatching { return LocalDateTime.parse(s, f).toLocalDate() }
            }
            return null
        }
    }
}
