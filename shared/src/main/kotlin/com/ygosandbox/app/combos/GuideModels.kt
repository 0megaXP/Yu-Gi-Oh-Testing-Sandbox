package com.ygosandbox.app.combos

import com.ygoengine.CardPriorities
import com.ygosandbox.app.platform.AppPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A card a guide talks about, with what it says. */
data class GuideCard(val code: Long, val name: String, val note: String = "")

/** A combo a guide explains (cards resolved where possible). */
data class GuideCombo(
    val name: String,
    val going: String,
    val hand: List<GuideCard>,
    val steps: List<ComboStep>,
    val endBoard: List<BoardCard>,
)

/** What one guide video says about the deck. */
data class DeckGuideReading(
    val summary: String,
    val strategy: String,
    val starters: List<GuideCard>,
    val extenders: List<GuideCard>,
    val searchFirst: List<GuideCard>,
    val protect: List<GuideCard>,
    val chokePoints: List<GuideCard>,
    val weakTo: List<GuideCard>,
    val buildNotes: List<GuideCard>,
    val combos: List<GuideCombo>,
    val tips: List<String>,
)

/** One guide video: found, read, or failed. */
data class GuideVideo(
    val url: String,
    val title: String,
    val uploader: String,
    val date: String,
    val seconds: Long,
    /** "read", "no_captions", "failed" */
    val status: String,
    val error: String = "",
    val reading: DeckGuideReading? = null,
    val readAt: Long = 0,
)

/** Every guide video read for one archetype search result (one deck in the Decks tab). */
data class GuideLibrary(val deckId: String, val archetypes: List<String>, val videos: List<GuideVideo>, val updatedAt: Long) {
    val readings: List<Pair<GuideVideo, DeckGuideReading>> get() = videos.mapNotNull { v -> v.reading?.let { v to it } }
}

/** A card with how many guides said it. */
data class Agreed(val card: GuideCard, val videos: Int, val notes: List<String>)

/** What the guides agree on, merged over every video read. */
data class GuideSummary(
    val videos: Int,
    val starters: List<Agreed>,
    val extenders: List<Agreed>,
    /** Best first: by how many guides name it, then by its average place in their order. */
    val searchFirst: List<Agreed>,
    val protect: List<Agreed>,
    val chokePoints: List<Agreed>,
    val weakTo: List<Agreed>,
    val buildNotes: List<Agreed>,
    val tips: List<Pair<String, String>>, // tip, video title
    val strategies: List<Pair<String, String>>, // summary, video title
) {
    /** For a duel bot playing [deck] (canonical ids): only the cards it plays; null when none apply. */
    fun priorities(deck: Set<Long>, canonical: (Long) -> Long): CardPriorities? {
        fun pick(l: List<Agreed>) = l.map { it.card.code }.filter { it != 0L && canonical(it) in deck }.distinct()
        val p = CardPriorities(pick(starters), pick(extenders), pick(searchFirst), pick(protect))
        return p.takeUnless { it.isEmpty }
    }

    /** Where this deck is stopped (its own cards), for the other bot. */
    fun chokes(deck: Set<Long>, canonical: (Long) -> Long): List<Long> =
        chokePoints.map { it.card.code }.filter { it != 0L && canonical(it) in deck }.distinct()

    companion object {
        fun of(lib: GuideLibrary): GuideSummary? {
            val r = lib.readings
            if (r.isEmpty()) return null
            fun agree(get: (DeckGuideReading) -> List<GuideCard>): List<Agreed> {
                val by = LinkedHashMap<String, MutableList<GuideCard>>()
                r.forEach { (_, g) -> get(g).distinctBy { key(it) }.forEach { by.getOrPut(key(it)) { ArrayList() } += it } }
                return by.values.map { l -> Agreed(l.first(), l.size, l.map { it.note }.filter { it.isNotBlank() }.distinct()) }
                    .sortedByDescending { it.videos }
            }
            // search order: guides that name a card, then its average place in their lists
            val places = HashMap<String, MutableList<Int>>()
            r.forEach { (_, g) -> g.searchFirst.distinctBy { key(it) }.forEachIndexed { i, c -> places.getOrPut(key(c)) { ArrayList() } += i } }
            val search = agree { it.searchFirst }.sortedWith(
                compareByDescending<Agreed> { it.videos }.thenBy { places[key(it.card)]?.average() ?: 99.0 })
            return GuideSummary(
                videos = r.size,
                starters = agree { it.starters },
                extenders = agree { it.extenders },
                searchFirst = search,
                protect = agree { it.protect },
                chokePoints = agree { it.chokePoints },
                weakTo = agree { it.weakTo },
                buildNotes = agree { it.buildNotes },
                tips = r.flatMap { (v, g) -> g.tips.map { it to v.title } },
                strategies = r.mapNotNull { (v, g) -> g.strategy.ifBlank { g.summary }.takeIf { it.isNotBlank() }?.let { it to v.title } },
            )
        }

        private fun key(c: GuideCard) = if (c.code != 0L) c.code.toString() else c.name.lowercase()
    }
}

/** Guide libraries on disk: files/guides/<deckId>.json. */
class GuideStore {
    private val dir get() = File(AppPlatform.current.filesDir, "guides").apply { mkdirs() }
    private fun file(deckId: String) = File(dir, deckId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    fun load(deckId: String): GuideLibrary =
        runCatching { decode(JSONObject(file(deckId).readText())) }.getOrNull() ?: GuideLibrary(deckId, emptyList(), emptyList(), 0)

    fun save(lib: GuideLibrary) {
        val f = file(lib.deckId)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(encode(lib).toString())
        com.ygosandbox.app.platform.replaceFile(tmp, f)
    }

    fun delete(deckId: String) { file(deckId).delete() }

    fun all(): List<GuideLibrary> =
        dir.listFiles { f -> f.name.endsWith(".json") }?.mapNotNull { runCatching { decode(JSONObject(it.readText())) }.getOrNull() }.orEmpty()

    // ---------------------------------------------------------------- json

    private fun card(c: GuideCard) = JSONObject().put("code", c.code).put("name", c.name).put("note", c.note)
    private fun cards(l: List<GuideCard>) = JSONArray(l.map(::card))
    private fun cardOf(o: JSONObject) = GuideCard(o.optLong("code"), o.optString("name"), o.optString("note"))
    private fun cardsOf(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> cardOf(it.getJSONObject(i)) } } ?: emptyList()
    private fun strings(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.optString(i) } } ?: emptyList()

    private fun encode(lib: GuideLibrary) = JSONObject().put("version", 1).put("deckId", lib.deckId)
        .put("archetypes", JSONArray(lib.archetypes)).put("updatedAt", lib.updatedAt)
        .put("videos", JSONArray(lib.videos.map { v ->
            JSONObject().put("url", v.url).put("title", v.title).put("uploader", v.uploader).put("date", v.date)
                .put("seconds", v.seconds).put("status", v.status).put("error", v.error).put("readAt", v.readAt)
                .put("reading", v.reading?.let { g ->
                    JSONObject().put("summary", g.summary).put("strategy", g.strategy)
                        .put("starters", cards(g.starters)).put("extenders", cards(g.extenders))
                        .put("searchFirst", cards(g.searchFirst)).put("protect", cards(g.protect))
                        .put("chokePoints", cards(g.chokePoints)).put("weakTo", cards(g.weakTo))
                        .put("buildNotes", cards(g.buildNotes)).put("tips", JSONArray(g.tips))
                        .put("combos", JSONArray(g.combos.map { c ->
                            JSONObject().put("name", c.name).put("going", c.going).put("hand", cards(c.hand))
                                .put("steps", JSONArray(c.steps.map { s ->
                                    JSONObject().put("action", s.action).put("code", s.code ?: 0L).put("name", s.name).put("detail", s.detail)
                                }))
                                .put("endBoard", JSONArray(c.endBoard.map { b -> JSONObject().put("code", b.code).put("name", b.name).put("where", b.where) }))
                        }))
                } ?: JSONObject.NULL)
        }))

    private fun decode(o: JSONObject): GuideLibrary {
        val videos = o.optJSONArray("videos")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty().map { v ->
            GuideVideo(
                v.optString("url"), v.optString("title"), v.optString("uploader"), v.optString("date"), v.optLong("seconds"),
                v.optString("status"), v.optString("error"),
                v.optJSONObject("reading")?.let { g ->
                    DeckGuideReading(
                        g.optString("summary"), g.optString("strategy"),
                        cardsOf(g.optJSONArray("starters")), cardsOf(g.optJSONArray("extenders")),
                        cardsOf(g.optJSONArray("searchFirst")), cardsOf(g.optJSONArray("protect")),
                        cardsOf(g.optJSONArray("chokePoints")), cardsOf(g.optJSONArray("weakTo")),
                        cardsOf(g.optJSONArray("buildNotes")),
                        g.optJSONArray("combos")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty().map { c ->
                            GuideCombo(
                                c.optString("name"), c.optString("going"), cardsOf(c.optJSONArray("hand")),
                                c.optJSONArray("steps")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty().map { s ->
                                    ComboStep(null, s.optString("action"), s.optLong("code").takeIf { it != 0L }, s.optString("name"), s.optString("detail"))
                                },
                                c.optJSONArray("endBoard")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty().map { b ->
                                    BoardCard(b.optLong("code"), b.optString("name"), b.optString("where"))
                                },
                            )
                        },
                        strings(g.optJSONArray("tips")),
                    )
                },
                v.optLong("readAt"),
            )
        }
        return GuideLibrary(o.optString("deckId"), strings(o.optJSONArray("archetypes")), videos, o.optLong("updatedAt"))
    }
}
