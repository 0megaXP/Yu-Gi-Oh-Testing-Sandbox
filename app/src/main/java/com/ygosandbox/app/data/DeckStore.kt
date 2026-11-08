package com.ygosandbox.app.data

import android.content.Context
import com.ygosandbox.app.model.CardStat
import com.ygosandbox.app.model.DeckAnalysis
import com.ygosandbox.app.model.GameFormat
import com.ygosandbox.app.model.SourceDeck
import com.ygosandbox.app.model.TimeSpan
import com.ygosandbox.app.model.Usage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saved deck analyses, kept privately on the phone in decks.json. */
class DeckStore(context: Context) {
    private val file = File(context.filesDir, "decks.json")

    fun load(): List<DeckAnalysis> = try {
        if (!file.exists()) emptyList()
        else {
            val a = JSONArray(file.readText())
            (0 until a.length()).mapNotNull { runCatching { fromJson(a.getJSONObject(it)) }.getOrNull() }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(list: List<DeckAnalysis>) {
        val a = JSONArray()
        list.forEach { a.put(toJson(it)) }
        val tmp = File(file.parentFile, "decks.json.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file)
    }

    private fun toJson(d: DeckAnalysis) = JSONObject().apply {
        put("id", d.id)
        put("archetypes", JSONArray(d.archetypes))
        put("excluded", JSONArray(d.excluded))
        put("format", d.format.name)
        put("span", d.span.name)
        put("createdAt", d.createdAt)
        put("since", d.since)
        put("scanned", d.decksScanned)
        put("cards", JSONArray().apply {
            d.cards.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.cardId); put("n", c.name); put("e", c.engine); put("u", c.usage.name)
                    put("m", c.mainRate.toDouble()); put("s", c.sideRate.toDouble()); put("a", c.anyRate.toDouble()); put("b", c.bothRate.toDouble())
                    put("am", c.avgMain.toDouble()); put("as", c.avgSide.toDouble()); put("x", c.extraDeck)
                })
            }
        })
        put("decks", JSONArray().apply {
            d.decks.forEach { s ->
                put(JSONObject().apply {
                    put("n", s.name); put("d", s.date); put("t", s.tournament); put("p", s.placement)
                    put("u", s.url); put("f", s.formatLabel); put("c", s.currentFormat)
                    put("cm", JSONArray(s.main)); put("ce", JSONArray(s.extra)); put("cs", JSONArray(s.side))
                })
            }
        })
    }

    private fun ids(a: JSONArray?): List<Long> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getLong(it) }

    private fun fromJson(o: JSONObject): DeckAnalysis {
        val arch = o.getJSONArray("archetypes")
        val cards = o.getJSONArray("cards")
        val decks = o.optJSONArray("decks") ?: JSONArray()
        return DeckAnalysis(
            id = o.getString("id"),
            archetypes = (0 until arch.length()).map { arch.getString(it) },
            excluded = o.optJSONArray("excluded")?.let { e -> (0 until e.length()).map { e.getString(it) } } ?: emptyList(),
            format = GameFormat.valueOf(o.getString("format")),
            span = TimeSpan.valueOf(o.getString("span")),
            createdAt = o.getLong("createdAt"),
            since = o.optString("since"),
            decksScanned = o.optInt("scanned"),
            cards = (0 until cards.length()).map { i ->
                val c = cards.getJSONObject(i)
                CardStat(
                    cardId = c.getLong("id"), name = c.getString("n"), engine = c.getBoolean("e"),
                    usage = Usage.valueOf(c.getString("u")),
                    mainRate = c.getDouble("m").toFloat(), sideRate = c.getDouble("s").toFloat(),
                    anyRate = c.getDouble("a").toFloat(), bothRate = c.optDouble("b", 0.0).toFloat(), avgMain = c.getDouble("am").toFloat(),
                    avgSide = c.getDouble("as").toFloat(), extraDeck = c.optBoolean("x"),
                )
            },
            decks = (0 until decks.length()).map { i ->
                val s = decks.getJSONObject(i)
                SourceDeck(
                    name = s.optString("n"), date = s.optString("d"), tournament = s.optString("t"),
                    placement = s.optString("p"), url = s.optString("u"), formatLabel = s.optString("f"),
                    currentFormat = s.optBoolean("c"),
                    main = ids(s.optJSONArray("cm")), extra = ids(s.optJSONArray("ce")), side = ids(s.optJSONArray("cs")),
                )
            },
        )
    }
}
