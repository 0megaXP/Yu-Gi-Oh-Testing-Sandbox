package com.ygosandbox.app.combos

import android.content.Context
import com.ygosandbox.app.data.CardDatabase
import com.ygosandbox.app.model.DeckAnalysis
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One step of a combo read from a video; [code] is null when the card couldn't be identified. */
data class ComboStep(val t: Double?, val action: String, val code: Long?, val name: String, val detail: String)

data class BoardCard(val code: Long, val name: String, val where: String) {
    val onField: Boolean get() = listOf("hand", "gy", "grave", "banish", "deck").none { it in where.lowercase() }
}

/** A combo read from one video that passed the deck-list check. */
data class VideoCombo(
    val url: String,
    val title: String,
    val uploader: String,
    val date: String,
    val going: String,
    val hand: List<Long>,
    val steps: List<ComboStep>,
    val endBoard: List<BoardCard>,
    val interruptions: List<String>,
    val chokePoints: List<String>,
    val uncertain: List<String>,
) {
    val fieldCodes: List<Long> get() = endBoard.filter { it.onField }.map { it.code }
}

/** A video that was read but left out, and why. */
data class DroppedVideo(val url: String, val title: String, val reason: String)

/** Everything read for one archetype search result (one deck in the Decks tab). */
data class ComboLibrary(val deckId: String, val videos: List<VideoCombo>, val dropped: List<DroppedVideo>, val updatedAt: Long) {
    val readUrls: Set<String> get() = (videos.map { it.url } + dropped.map { it.url }).toSet()
}

/** Videos whose end boards share most field cards: shown as one line, most-seen first. */
data class ComboLine(val videos: List<VideoCombo>, val coreBoard: List<Long>) {
    /** The video whose board agrees most with the others; its steps are shown. */
    val main: VideoCombo get() = videos.first()
}

object Lines {
    private const val SAME_LINE = 0.6

    private fun jaccard(a: List<Long>, b: List<Long>): Double {
        val x = a.toSet(); val y = b.toSet()
        val union = (x + y).size
        return if (union == 0) 0.0 else (x intersect y).size.toDouble() / union
    }

    fun group(videos: List<VideoCombo>): List<ComboLine> {
        val groups = ArrayList<MutableList<VideoCombo>>()
        for (v in videos) {
            val g = groups.firstOrNull { g -> g.any { jaccard(it.fieldCodes, v.fieldCodes) >= SAME_LINE } }
            if (g != null) g += v else groups += mutableListOf(v)
        }
        return groups.map { g ->
            val sorted = g.sortedByDescending { v -> g.sumOf { jaccard(it.fieldCodes, v.fieldCodes) } }
            val tally = g.flatMap { it.fieldCodes.distinct() }.groupingBy { it }.eachCount()
            val core = tally.filter { it.value * 2 >= g.size }.entries.sortedByDescending { it.value }.map { it.key }
            ComboLine(sorted, core)
        }.sortedWith(compareByDescending<ComboLine> { it.videos.size }.thenByDescending { it.main.steps.size })
    }
}

/**
 * Card names read off a screen -> cards. Names snap to the deck's own cards first, so OCR noise
 * ("Ash Blosom") lands on a real card in the list; a name that clearly matches another card
 * still resolves to that card, which is how cards outside the lists get spotted.
 */
class CardNames(private val db: CardDatabase) {
    private val byNorm: Map<String, Long> = HashMap<String, Long>().also { m ->
        db.cards.forEach { c -> m.putIfAbsent(norm(c.name), c.id) }
    }

    fun canonical(id: Long): Long = db.find(id)?.id ?: id
    fun name(id: Long): String = db.find(id)?.name ?: id.toString()

    fun resolve(text: String, prefer: Set<Long>): Long? {
        val key = norm(text)
        if (key.isEmpty()) return null
        val pools = listOf(prefer.associateBy { norm(name(it)) }, byNorm)
        pools.forEachIndexed { i, pool ->
            pool[key]?.let { return it }
            val starts = pool.keys.filter { it.startsWith(key) }
            if (starts.size == 1 && key.length >= 6) return pool[starts[0]]
            if (starts.size > 1) return null // a cut-off name that fits several cards: better unknown than wrong
            val cutoff = if (i == 0) 0.8 else 0.86
            val best = pool.keys.maxByOrNull { similarity(key, it) }
            if (best != null && similarity(key, best) >= cutoff) return pool[best]
        }
        return null
    }

    companion object {
        private val PUNCT = Regex("""["'’“”!?.,:\-–—&()]""")
        fun norm(s: String) = s.lowercase().replace(PUNCT, " ").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

        /** 2·matches / total length, like Python's difflib ratio, on bigrams to stay fast on 13k names. */
        fun similarity(a: String, b: String): Double {
            if (a == b) return 1.0
            if (a.length < 2 || b.length < 2) return 0.0
            val x = a.windowed(2).groupingBy { it }.eachCount()
            val y = b.windowed(2).groupingBy { it }.eachCount()
            val common = x.entries.sumOf { (k, n) -> minOf(n, y[k] ?: 0) }
            return 2.0 * common / (a.length - 1 + b.length - 1)
        }
    }
}

/** The cards a video's combo may use: every card any tournament list in this result plays. */
fun allowedCards(deck: DeckAnalysis, names: CardNames): Set<Long> {
    val ids = deck.decks.flatMap { it.main + it.extra + it.side } + deck.cards.map { it.cardId }
    return ids.map { names.canonical(it) }.toSet()
}

/** API key and model for reading videos, kept apart from the app's other settings. */
class ComboSettings(context: Context) {
    private val prefs = context.getSharedPreferences("combos", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = prefs.getString("model", "")?.ifBlank { null } ?: ComboReader.DEFAULT_MODEL
        set(v) = prefs.edit().putString("model", v.trim()).apply()
}

/** Libraries saved per deck id in combos/<id>.json. */
class ComboStore(context: Context) {
    private val dir = File(context.filesDir, "combos")

    private fun file(deckId: String) = File(dir, deckId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")

    fun load(deckId: String): ComboLibrary = runCatching { decode(JSONObject(file(deckId).readText())) }
        .getOrDefault(ComboLibrary(deckId, emptyList(), emptyList(), 0))

    fun save(lib: ComboLibrary) {
        dir.mkdirs()
        val f = file(lib.deckId)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(encode(lib).toString())
        tmp.renameTo(f)
    }

    fun delete(deckId: String) { file(deckId).delete() }

    private fun strings(l: List<String>) = JSONArray(l)
    private fun JSONArray?.strings() = this?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
    private fun JSONArray?.objects() = this?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

    private fun encode(lib: ComboLibrary) = JSONObject()
        .put("version", 1)
        .put("deckId", lib.deckId)
        .put("updatedAt", lib.updatedAt)
        .put("videos", JSONArray(lib.videos.map { v ->
            JSONObject().put("url", v.url).put("title", v.title).put("uploader", v.uploader).put("date", v.date)
                .put("going", v.going).put("hand", JSONArray(v.hand))
                .put("steps", JSONArray(v.steps.map { s ->
                    JSONObject().put("t", s.t ?: JSONObject.NULL).put("action", s.action)
                        .put("code", s.code ?: JSONObject.NULL).put("name", s.name).put("detail", s.detail)
                }))
                .put("endBoard", JSONArray(v.endBoard.map { JSONObject().put("code", it.code).put("name", it.name).put("where", it.where) }))
                .put("interruptions", strings(v.interruptions)).put("chokePoints", strings(v.chokePoints))
                .put("uncertain", strings(v.uncertain))
        }))
        .put("dropped", JSONArray(lib.dropped.map { JSONObject().put("url", it.url).put("title", it.title).put("reason", it.reason) }))

    private fun decode(o: JSONObject) = ComboLibrary(
        deckId = o.getString("deckId"),
        updatedAt = o.optLong("updatedAt"),
        videos = o.optJSONArray("videos").objects().map { v ->
            VideoCombo(
                url = v.optString("url"), title = v.optString("title"), uploader = v.optString("uploader"),
                date = v.optString("date"), going = v.optString("going"),
                hand = v.optJSONArray("hand")?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList(),
                steps = v.optJSONArray("steps").objects().map { s ->
                    ComboStep(
                        if (s.isNull("t")) null else s.optDouble("t"), s.optString("action"),
                        if (s.isNull("code")) null else s.optLong("code"), s.optString("name"), s.optString("detail"),
                    )
                },
                endBoard = v.optJSONArray("endBoard").objects().map { BoardCard(it.getLong("code"), it.optString("name"), it.optString("where")) },
                interruptions = v.optJSONArray("interruptions").strings(),
                chokePoints = v.optJSONArray("chokePoints").strings(),
                uncertain = v.optJSONArray("uncertain").strings(),
            )
        },
        dropped = o.optJSONArray("dropped").objects().map { DroppedVideo(it.optString("url"), it.optString("title"), it.optString("reason")) },
    )
}
