package com.ygosandbox.app.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.ygosandbox.app.model.Card
import com.ygosandbox.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * The full Yu-Gi-Oh! card list, downloaded once from the YGOPRODeck API and kept on the phone
 * in a compact file. It is refreshed when YGOPRODeck publishes a new database version.
 */
class CardDatabase(private val context: Context) {
    private val file = File(context.filesDir, "cards.json")
    private val archetypeFile = File(context.filesDir, "archetypes.json")

    @Volatile var cards: List<Card> = emptyList(); private set
    @Volatile private var byId: Map<Long, Card> = emptyMap()
    @Volatile var archetypes: List<String> = emptyList(); private set

    val isDownloaded: Boolean get() = file.exists() && file.length() > 0

    fun find(id: Long): Card? = byId[id]

    /** Loads the copy saved on the phone. Returns false if there is none yet. */
    suspend fun loadLocal(): Boolean = withContext(Dispatchers.IO) {
        if (!isDownloaded) return@withContext false
        val list = ArrayList<Card>(14_000)
        JsonReader(file.bufferedReader()).use { r ->
            r.beginArray()
            while (r.hasNext()) list += readCompact(r)
            r.endArray()
        }
        setCards(list)
        archetypes = if (archetypeFile.exists()) {
            val a = JSONArray(archetypeFile.readText())
            (0 until a.length()).map { a.getString(it) }
        } else archetypesFromCards(list)
        true
    }

    /** Current database version published by YGOPRODeck. */
    suspend fun remoteVersion(): String {
        val a = JSONArray(Http.get("$API/checkDBVer.php"))
        val o = a.getJSONObject(0)
        return o.optString("database_version") + "|" + o.optString("last_update")
    }

    /** Downloads the full card list and replaces the local copy. */
    suspend fun download(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val raw = File(context.cacheDir, "cardinfo-download.json")
        try {
            Http.download("$API/cardinfo.php", raw) { onProgress(it * 0.85f) }
            val list = ArrayList<Card>(14_000)
            JsonReader(raw.bufferedReader()).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() == "data") {
                        r.beginArray()
                        while (r.hasNext()) readApiCard(r)?.let { list += it }
                        r.endArray()
                    } else r.skipValue()
                }
                r.endObject()
            }
            if (list.size < 1000) throw Exception("The card list looks incomplete (${list.size} cards)")
            onProgress(0.9f)
            val tmp = File(context.filesDir, "cards.json.tmp")
            JsonWriter(tmp.bufferedWriter()).use { w ->
                w.beginArray()
                list.forEach { writeCompact(w, it) }
                w.endArray()
            }
            tmp.renameTo(file)

            val names = try {
                val a = JSONArray(Http.get("$API/archetypes.php"))
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("archetype_name") }
                    .filter { it.isNotBlank() }
            } catch (e: Exception) {
                emptyList()
            }
            val all = (names + archetypesFromCards(list)).distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
            archetypeFile.writeText(JSONArray(all).toString())
            archetypes = all
            setCards(list)
            onProgress(1f)
        } finally {
            raw.delete()
        }
    }

    private fun setCards(list: List<Card>) {
        val map = HashMap<Long, Card>(list.size * 2)
        list.forEach { c -> c.altIds.forEach { map[it] = c }; map[c.id] = c }
        cards = list.sortedBy { it.name.lowercase() }
        byId = map
    }

    private fun archetypesFromCards(list: List<Card>) =
        list.map { it.archetype }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }

    // ---------- Parsing ----------

    private fun readApiCard(r: JsonReader): Card? {
        var id = 0L; var name = ""; var type = ""; var frame = ""; var desc = ""; var race = ""
        var attribute = ""; var archetype = ""
        var atk: Int? = null; var def: Int? = null; var level: Int? = null; var linkval: Int? = null; var scale: Int? = null
        var banTcg = ""; var banOcg = ""
        val alt = ArrayList<Long>(2)
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "id" -> id = r.nextLong()
                "name" -> name = str(r)
                "type" -> type = str(r)
                "frameType" -> frame = str(r)
                "desc" -> desc = str(r)
                "race" -> race = str(r)
                "attribute" -> attribute = str(r)
                "archetype" -> archetype = str(r)
                "atk" -> atk = int(r)
                "def" -> def = int(r)
                "level" -> level = int(r)
                "linkval" -> linkval = int(r)
                "scale" -> scale = int(r)
                "banlist_info" -> {
                    r.beginObject()
                    while (r.hasNext()) when (r.nextName()) {
                        "ban_tcg" -> banTcg = str(r)
                        "ban_ocg" -> banOcg = str(r)
                        else -> r.skipValue()
                    }
                    r.endObject()
                }
                "card_images" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        r.beginObject()
                        while (r.hasNext()) {
                            if (r.nextName() == "id") alt += r.nextLong() else r.skipValue()
                        }
                        r.endObject()
                    }
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (id == 0L || name.isEmpty()) return null
        // Skeleton/token entries and Rush Duel cards aren't deck-buildable here.
        if (frame == "skill" || frame == "token") return null
        if (!alt.contains(id)) alt.add(0, id)
        return Card(
            id = id, name = name, type = type, frameType = frame, desc = desc, race = race,
            attribute = attribute, atk = atk, def = def, level = linkval ?: level, scale = scale,
            archetype = archetype, banTcg = banTcg, banOcg = banOcg, altIds = alt,
        )
    }

    private fun writeCompact(w: JsonWriter, c: Card) {
        w.beginObject()
        w.name("i").value(c.id)
        w.name("n").value(c.name)
        w.name("t").value(c.type)
        w.name("f").value(c.frameType)
        w.name("d").value(c.desc)
        w.name("r").value(c.race)
        if (c.attribute.isNotEmpty()) w.name("a").value(c.attribute)
        c.atk?.let { w.name("k").value(it.toLong()) }
        c.def?.let { w.name("e").value(it.toLong()) }
        c.level?.let { w.name("l").value(it.toLong()) }
        c.scale?.let { w.name("s").value(it.toLong()) }
        if (c.archetype.isNotEmpty()) w.name("h").value(c.archetype)
        if (c.banTcg.isNotEmpty()) w.name("bt").value(c.banTcg)
        if (c.banOcg.isNotEmpty()) w.name("bo").value(c.banOcg)
        if (c.altIds.size > 1) {
            w.name("x").beginArray()
            c.altIds.forEach { w.value(it) }
            w.endArray()
        }
        w.endObject()
    }

    private fun readCompact(r: JsonReader): Card {
        var id = 0L; var name = ""; var type = ""; var frame = ""; var desc = ""; var race = ""
        var attribute = ""; var archetype = ""; var banTcg = ""; var banOcg = ""
        var atk: Int? = null; var def: Int? = null; var level: Int? = null; var scale: Int? = null
        val alt = ArrayList<Long>(1)
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "i" -> id = r.nextLong()
                "n" -> name = r.nextString()
                "t" -> type = r.nextString()
                "f" -> frame = r.nextString()
                "d" -> desc = r.nextString()
                "r" -> race = r.nextString()
                "a" -> attribute = r.nextString()
                "k" -> atk = r.nextInt()
                "e" -> def = r.nextInt()
                "l" -> level = r.nextInt()
                "s" -> scale = r.nextInt()
                "h" -> archetype = r.nextString()
                "bt" -> banTcg = r.nextString()
                "bo" -> banOcg = r.nextString()
                "x" -> { r.beginArray(); while (r.hasNext()) alt += r.nextLong(); r.endArray() }
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (alt.isEmpty()) alt += id
        return Card(id, name, type, frame, desc, race, attribute, atk, def, level, scale, archetype, banTcg, banOcg, alt)
    }

    private fun str(r: JsonReader): String =
        if (r.peek() == JsonToken.NULL) { r.nextNull(); "" } else r.nextString()

    private fun int(r: JsonReader): Int? = when (r.peek()) {
        JsonToken.NULL -> { r.nextNull(); null }
        JsonToken.NUMBER -> r.nextInt()
        JsonToken.STRING -> r.nextString().toIntOrNull()
        else -> { r.skipValue(); null }
    }

    companion object {
        const val API = "https://db.ygoprodeck.com/api/v7"

        /**
         * A card belongs to an archetype when YGOPRODeck tags it with that archetype, or when the
         * archetype's name appears as a whole word in the card's name (that's how the game itself
         * defines archetypes, and it catches cards YGOPRODeck lists under another archetype), or when
         * the card's text cites the archetype in quotes, e.g. Add 1 "Branded" card.
         */
        fun isInArchetype(card: Card, archetype: String): Boolean {
            if (card.archetype.equals(archetype, ignoreCase = true)) return true
            if (nameContains(card.name, archetype)) return true
            return citesInText(card.desc, archetype)
        }

        /**
         * True when [text] mentions the archetype exactly as a quoted name ("Branded", “Branded”).
         * Only exact quotes count, so "Dark Magician" doesn't make a card part of "Magician".
         */
        fun citesInText(text: String, archetype: String): Boolean {
            val a = archetype.trim()
            if (a.isEmpty()) return false
            return QUOTES.any { (open, close) -> text.contains("$open$a$close", ignoreCase = true) }
        }

        private val QUOTES = listOf("\"" to "\"", "“" to "”", "'" to "'")

        fun nameContains(name: String, word: String): Boolean {
            if (word.isBlank()) return false
            var from = 0
            while (true) {
                val i = name.indexOf(word, from, ignoreCase = true)
                if (i < 0) return false
                val before = if (i == 0) ' ' else name[i - 1]
                val end = i + word.length
                val after = if (end >= name.length) ' ' else name[end]
                if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
                from = i + 1
            }
        }
    }
}
