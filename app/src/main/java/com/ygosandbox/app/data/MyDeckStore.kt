package com.ygosandbox.app.data

import android.content.Context
import com.ygosandbox.app.model.GameFormat
import com.ygosandbox.app.model.Role
import com.ygosandbox.app.model.SavedDeck
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The user's own decks, kept privately on the phone in mydecks.json. */
class MyDeckStore(context: Context) {
    private val file = File(context.filesDir, "mydecks.json")

    fun load(): List<SavedDeck> = try {
        if (!file.exists()) emptyList()
        else {
            val a = JSONArray(file.readText())
            (0 until a.length()).mapNotNull { runCatching { fromJson(a.getJSONObject(it)) }.getOrNull() }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(list: List<SavedDeck>) {
        val a = JSONArray()
        list.forEach { a.put(toJson(it)) }
        val tmp = File(file.parentFile, "mydecks.json.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file)
    }

    private fun toJson(d: SavedDeck) = JSONObject().apply {
        put("id", d.id)
        put("name", d.name)
        put("format", d.format.name)
        put("main", JSONArray(d.main))
        put("extra", JSONArray(d.extra))
        put("side", JSONArray(d.side))
        put("created", d.createdAt)
        put("updated", d.updatedAt)
        put("roles", JSONObject().apply {
            d.roles.forEach { (id, r) -> put(id.toString(), JSONArray(r.map { it.name })) }
        })
    }

    private fun ids(a: JSONArray?): List<Long> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getLong(it) }

    private fun fromJson(o: JSONObject) = SavedDeck(
        id = o.getString("id"),
        name = o.optString("name").ifBlank { "Deck" },
        format = runCatching { GameFormat.valueOf(o.getString("format")) }.getOrDefault(GameFormat.TCG),
        main = ids(o.optJSONArray("main")),
        extra = ids(o.optJSONArray("extra")),
        side = ids(o.optJSONArray("side")),
        createdAt = o.optLong("created"),
        updatedAt = o.optLong("updated"),
        roles = o.optJSONObject("roles")?.let { r ->
            r.keys().asSequence().mapNotNull { k ->
                val id = k.toLongOrNull() ?: return@mapNotNull null
                val a = r.getJSONArray(k)
                id to (0 until a.length()).mapNotNull { Role.parse(a.getString(it)) }.toSet()
            }.toMap()
        } ?: emptyMap(),
    )
}
