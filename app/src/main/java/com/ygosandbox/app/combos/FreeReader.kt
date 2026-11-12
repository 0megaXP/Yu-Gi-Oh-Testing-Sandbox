package com.ygosandbox.app.combos

import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Reads a video for free, on the phone, with text recognition (ML Kit, offline).
 *
 * Two kinds of text are used:
 * - **A duel log** (Duelingbook, Dueling Nexus and others show the actions as text): a line with
 *   an action word and a card name ("Special Summoned "X"", "Activated X") gives the action,
 *   and a "Player: …" prefix gives who did it. Turn and win lines mark turns and games.
 * - **Card names alone** (activation banners, card panels): the cards in the order they appear,
 *   as "use" steps, when the video has no log.
 * A line counts when it appears, not while it stays on screen.
 */
object FreeReader {

    /** One thing read off the screen. [action] is a video-reader action, "use", "turn" or "game_end". */
    data class Event(val t: Double, val code: Long?, val action: String, val player: String?, val exact: Boolean, val text: String)

    class Result(
        /** Everything read, in order. */
        val events: List<Event>,
        /** The video has a duel log (actions and players come from it). */
        val log: Boolean,
        val hand: List<Long>,
        /** What the steps were read from. */
        val source: Source,
    ) {
        /** The cards played, in order (combo steps). */
        val cards: List<Event> get() = events.filter { it.code != null && it.action !in setOf("turn", "game_end", "attack", "draw") }
        val players: List<String> get() = events.mapNotNull { it.player }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.take(2).map { it.key }
    }

    // Action words, most specific first.
    private val VERBS = listOf(
        "link summon" to "link_summon", "xyz summon" to "xyz_summon", "synchro summon" to "synchro_summon",
        "fusion summon" to "fusion_summon", "ritual summon" to "ritual_summon", "pendulum summon" to "pendulum_summon",
        "normal summon" to "normal_summon", "tribute summon" to "normal_summon", "special summon" to "special_summon",
        "activat" to "activate", "banish" to "banish", "sent" to "send_to_gy", "to gy" to "send_to_gy",
        "to the gy" to "send_to_gy", "graveyard" to "send_to_gy", "discard" to "send_to_gy", "added" to "search",
        "to hand" to "search", "to the hand" to "search", "attach" to "attach", "detach" to "send_to_gy",
        "overlay" to "attach", "attack" to "attack", "drew" to "draw", "drawn" to "draw",
    )
    private val SET = Regex("""\bset\b""")
    private val PLAYER = Regex("""^([^:"“”]{2,24}):\s*(.+)$""")
    private val QUOTED = Regex("""["“”']([^"“”]{3,70})["“”']""")
    private val TURN = Regex("""\bturn\s*(\d{1,2})\b""")
    private val END = listOf("won the duel", "wins the duel", "won the match", "wins the match", "surrender", "admitted defeat",
        "lost the duel", "has won", " wins", " won")

    private fun actionOf(lower: String): String? =
        VERBS.firstOrNull { it.first in lower }?.second ?: if (SET.containsMatchIn(lower)) "set" else null

    suspend fun read(
        frames: List<Frame>,
        cn: CardNames,
        allowed: Set<Long>,
        handTraps: Set<Long>,
        title: String,
        description: String,
        extraDeck: Set<Long>,
        captions: String = "",
        onProgress: (Float) -> Unit,
    ): Result = withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val pool = allowed + handTraps
        val logEvents = ArrayList<Event>()
        val nameEvents = ArrayList<Event>()
        var logFrames = 0
        val lastSeen = HashMap<Long, Int>()        // frame index a card's name was last on screen
        val lastEvent = HashMap<Long, Double>()    // when a card last counted
        try {
            frames.forEachIndexed { i, f ->
                coroutineContext.ensureActive()
                onProgress(i.toFloat() / frames.size)
                val bmp = BitmapFactory.decodeFile(f.file.absolutePath) ?: return@forEachIndexed
                val text = runCatching { Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0))) }.getOrNull()
                bmp.recycle()
                text ?: return@forEachIndexed
                // lines top to bottom: a log grows downwards, so new lines come in reading order
                val boxed = text.textBlocks.flatMap { it.lines }.sortedBy { it.boundingBox?.top ?: 0 }
                val lines = boxed.map { it.text.trim() }

                // --- duel log: the entries on screen, stitched onto what was read so far (it scrolls)
                val here = logEntries(boxed, cn, pool, f.seconds)
                if (here.size >= 2) logFrames++
                if (here.isNotEmpty()) stitch(logEvents, here)

                // --- card names alone: only a line that is just the name (a banner or card title),
                // not a sentence or effect text that mentions other cards; also wrapped over two lines
                val found = LinkedHashSet<Long>()
                for (c in lines + lines.zipWithNext { a, b -> "$a $b" }) {
                    if (c.length < 5 || c.length > 60) continue
                    cn.match(c, pool, 0.88, whole = true)?.let { found += cn.canonical(it) }
                }
                val fresh = found.filter { id ->
                    // a card counts again only after it left the screen for a while
                    val gone = i - (lastSeen[id] ?: -100)
                    gone >= 3 && f.seconds - (lastEvent[id] ?: -100.0) >= 6
                }
                // many names appearing at once is an explanation box or a deck list, not a play
                if (fresh.size < 4) fresh.forEach { id ->
                    nameEvents += Event(f.seconds, id, "use", null, false, cn.name(id))
                    lastEvent[id] = f.seconds
                }
                found.forEach { lastSeen[it] = i }
            }
        } finally {
            recognizer.close()
        }
        // What to go by, best first: a duel log on screen; else the narration (cards said with an
        // action, in the order said); else card names standing alone on screen.
        val log = logFrames >= 5 && logEvents.count { it.code != null } >= 3
        val spoken = if (captions.isBlank()) Spoken(emptyList(), emptyList()) else fromCaptions(captions, cn, pool)
        val narrated = !log && spoken.steps.count { it.code !in handTraps } >= 3
        val events = when {
            log -> logEvents
            narrated -> spoken.steps
            else -> nameEvents.filter { it.code !in handTraps }
        }
        val source = when { log -> Source.LOG; narrated -> Source.NARRATION; else -> Source.SCREEN }

        // The opening hand: what the narration says is in hand, else the cards the title or
        // description names, else the first Main Deck cards played.
        val named = (title.split(Regex("[,/+:|\\-–]| and | with ")) + description.lines().take(30))
            .mapNotNull { s -> s.trim().takeIf { it.length >= 5 }?.let { cn.match(it, allowed, 0.9) } }
            .map { cn.canonical(it) }.filter { it !in extraDeck }.distinct()
        val firstPlayed = events.mapNotNull { it.code }.filter { it in allowed && it !in extraDeck && it !in handTraps }.distinct().take(3)
        val said = spoken.hand.filter { it in allowed && it !in extraDeck }
        Result(events, log, (said.ifEmpty { named } + firstPlayed).distinct().take(5), source)
    }

    enum class Source { LOG, NARRATION, SCREEN }

    private class Spoken(val steps: List<Event>, val hand: List<Long>)

    private val STOP = ("with and to the a an we then so now our this that it its into for of in on from using use just you i um uh " +
        "yeah first next summon summons summoning add adds search searches set sets activate activates link special normal " +
        "discard send banish xyz synchro fusion two one my your their").split(' ').toSet()
    private val SPOKEN_VERBS = listOf(
        "link summon" to "link_summon", "xyz summon" to "xyz_summon", "synchro summon" to "synchro_summon",
        "fusion summon" to "fusion_summon", "normal summon" to "normal_summon", "special summon" to "special_summon",
        "summon" to "special_summon", "activat" to "activate", "search" to "search", "add" to "search", "send" to "send_to_gy",
        "discard" to "send_to_gy", "banish" to "banish", "set" to "set", "use" to "activate",
    )
    // "if they have Ash, go into Shatranga instead": a line about what could happen, not a play
    private val HYPOTHETICAL = Regex("""\b(if|instead|could|would|otherwise|in case|alternatively|or you)\b""")
    private val HAND_CUE = Regex("""\b(hand|start|starting|opening|open)\b""")
    private val STAMP = Regex("""^\[(\d+):(\d\d)]\s*(.*)$""")

    /** Cards the narration names: with an action word before them, steps; in a line about the hand, the hand. */
    private fun fromCaptions(captions: String, cn: CardNames, pool: Set<Long>): Spoken {
        val steps = ArrayList<Event>()
        val hand = ArrayList<Long>()
        val lines = captions.lines()
        lines.forEachIndexed { li, line ->
            val m = STAMP.find(line) ?: return@forEachIndexed
            val t = m.groupValues[1].toInt() * 60.0 + m.groupValues[2].toInt()
            val text = m.groupValues[3].lowercase()
            if (HYPOTHETICAL.containsMatchIn(text)) return@forEachIndexed
            val words = CardNames.norm(text).split(' ').filter { it.isNotEmpty() }
            var i = 0
            while (i < words.size) {
                if (words[i] in STOP) { i++; continue }
                var best: Triple<Int, Long, Double>? = null
                for (len in 1..6) {
                    if (i + len > words.size || words[i + len - 1] in STOP) continue
                    val hit = cn.matchSpokenScored(words.subList(i, i + len).joinToString(" "), pool, 0.75) ?: continue
                    if (best == null || hit.second > best.third + 0.02) best = Triple(len, hit.first, hit.second)
                }
                if (best == null) { i++; continue }
                val (len, id, _) = best
                val action = verbBefore(words, i)
                    ?: words.getOrNull(i + len)?.takeIf { it.startsWith("trigger") || it.startsWith("effect") || it.startsWith("activat") }
                        ?.let { "activate" }
                val code = cn.canonical(id)
                when {
                    action != null -> if (steps.lastOrNull()?.let { it.code == code && t - it.t < 6 } != true)
                        steps += Event(t, code, action, null, false, text)
                    HAND_CUE.containsMatchIn(text) && li < lines.size / 4 -> hand += code
                }
                i += len
            }
        }
        return Spoken(steps, hand.distinct())
    }

    /** The action word closest before word [i]: two-word ones ("normal summon") checked first. */
    private fun verbBefore(words: List<String>, i: Int): String? {
        for (j in i - 1 downTo maxOf(0, i - 4)) {
            val two = if (j > 0) words[j - 1] + " " + words[j] else ""
            SPOKEN_VERBS.firstOrNull { (v, _) -> ' ' in v && two.startsWith(v) }?.let { return it.second }
            SPOKEN_VERBS.firstOrNull { (v, _) -> ' ' !in v && words[j].startsWith(v) }?.let { return it.second }
        }
        return null
    }

    // Log verbs, compared with spaces removed (text recognition sometimes drops them:
    // "LevyRozmandeclaredeffectof"). Most specific first.
    private val LOG_VERBS = listOf(
        "declaredeffectof" to "activate", "activated" to "activate", "normalsummoned" to "normal_summon",
        "tributesummoned" to "normal_summon", "specialsummoned" to "special_summon", "flipsummoned" to "special_summon",
        "summoned" to "special_summon", "banished" to "banish", "discarded" to "send_to_gy", "detached" to "send_to_gy",
        "sent" to "send_to_gy", "attached" to "attach", "added" to "search", "returned" to "search", "set" to "set",
    )

    /** A log header in [raw]: (action, player, the text after the verb), or null. */
    private fun header(raw: String): Triple<String, String, String>? {
        val quote = raw.indexOfFirst { it == '"' || it == '“' || it == '”' }
        val before = if (quote >= 0) raw.substring(0, quote) else raw
        val compact = before.lowercase().filter { it.isLetterOrDigit() }
        for ((verb, action) in LOG_VERBS) {
            val i = compact.lastIndexOf(verb)
            // the verb ends the text before the card name, after a player name
            if (i < 2 || i + verb.length != compact.length) continue
            return Triple(action, compact.substring(0, i), if (quote >= 0) raw.substring(quote) else "")
        }
        return null
    }

    /**
     * The duel log entries in one frame, top to bottom. A log (Duelingbook's, for one) writes
     * `Player declared effect of` with the card name in quotes, often on the next line; text
     * recognition may drop the spaces ("AngelechyDestrier"). Only lines in the log's column
     * count, so quoted names in a card's effect text elsewhere on screen are ignored.
     */
    private fun logEntries(boxed: List<com.google.mlkit.vision.text.Text.Line>, cn: CardNames, pool: Set<Long>, t: Double): List<Event> {
        val headers = boxed.filter { header(it.text.trim()) != null }
        if (headers.isEmpty()) return emptyList()
        val column = headers.mapNotNull { it.boundingBox?.left }.sorted().let { it[it.size / 2] }
        val width = boxed.maxOfOrNull { it.boundingBox?.right ?: 0 } ?: 1000
        val inColumn = boxed.filter { l -> l.boundingBox?.left?.let { kotlin.math.abs(it - column) < width / 20 } == true }
        val out = ArrayList<Event>()
        var pending: Pair<String, String?>? = null  // action, player of a header waiting for its card
        for (line in inColumn) {
            val raw = line.text.trim()
            val h = header(raw)
            if (h != null) {
                val (action, player, rest) = h
                val id = quotedCard(rest, cn, pool)
                if (id != null) { out += Event(t, id, action, player, true, raw); pending = null }
                else pending = action to player
                continue
            }
            val id = quotedCard(raw, cn, pool) ?: continue
            val p = pending ?: continue   // a quoted name with no header above it: not a log entry
            out += Event(t, id, p.first, p.second, true, raw)
            pending = null
        }
        return out
    }

    /** The card named in quotes in [text] (spaces optional), or null. */
    private fun quotedCard(text: String, cn: CardNames, pool: Set<Long>): Long? {
        val q = text.trim().trim('"', '“', '”', '\'', ' ')
        if (q.length < 3) return null
        if (!text.contains('"') && !text.contains('“') && !text.contains('”')) return null
        return (cn.exactLoose(q) ?: cn.match(q, pool, 0.85))?.let { cn.canonical(it) }
    }

    /**
     * Appends the entries of a new frame to [seq]. The log scrolls, so the frame mostly repeats
     * the last entries read: the frame is aligned with them (longest common subsequence, in
     * order) and what comes after the last aligned entry is new. A line text recognition missed
     * in one frame doesn't break the alignment, and a card played twice in a row isn't lost.
     * Tested on a real Duelingbook video (frames every 2 s, card text panel in view).
     */
    private fun stitch(seq: MutableList<Event>, frame: List<Event>) {
        fun same(a: Event, b: Event) = a.code == b.code && a.action == b.action
        val tail = seq.takeLast(frame.size + 4)
        val n = tail.size
        val m = frame.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            lcs[i][j] = if (same(tail[i], frame[j])) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        var i = 0
        var j = 0
        var lastI = -1
        var lastJ = -1
        while (i < n && j < m) {
            when {
                same(tail[i], frame[j]) -> { lastI = i; lastJ = j; i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> i++
                else -> j++
            }
        }
        val matched = lcs[0][0]
        when {
            // it lines up with the end of what was read: the rest is new
            matched >= minOf(2, m) && lastI >= n - 2 -> seq += frame.subList(lastJ + 1, m)
            // the first frame, or the log moved on more than a screen
            matched == 0 || seq.isEmpty() -> seq += frame
            // otherwise an older part of the log (scrolled back, or misread): nothing new
        }
    }

    /** A duel log line: an action with a card, a turn line or a win line; null for anything else. */
    private fun parseLogLine(raw: String, cn: CardNames, pool: Set<Long>): Event? {
        var player: String? = null
        var body = raw
        PLAYER.find(raw)?.let { m -> player = m.groupValues[1].trim(); body = m.groupValues[2] }
        val lower = body.lowercase()
        TURN.find(lower)?.let { m ->
            if (lower.length < 40) return Event(0.0, null, "turn", player, true, "Turn ${m.groupValues[1]}")
        }
        if (END.any { it in lower } && lower.length < 80) return Event(0.0, null, "game_end", player ?: body.substringBefore(' '), true, body)
        val action = actionOf(lower) ?: return null
        // a quoted name is exact and may be any card (the opponent's too); otherwise only this deck's cards
        val quoted = QUOTED.findAll(body).map { it.groupValues[1] }.toList()
        for (q in quoted) {
            cn.exact(q)?.let { return Event(0.0, cn.canonical(it), action, player, true, raw) }
            cn.match(q, pool, 0.85)?.let { return Event(0.0, cn.canonical(it), action, player, false, raw) }
        }
        val id = cn.match(body, pool, 0.85) ?: return null
        return Event(0.0, cn.canonical(id), action, player, false, raw)
    }
}
