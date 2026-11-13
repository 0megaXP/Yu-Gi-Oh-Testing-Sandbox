package com.ygosandbox.app.data

import com.ygosandbox.app.platform.AppPlatform

import org.json.JSONObject
import java.io.File

/**
 * The engine's learned move policy: one for all decks, kept on the phone (policy.json).
 *
 * Building reference boards and checking video lines train it further; every engine search
 * (Play engine, combo tree, bots) uses it to try the options good lines choose first.
 */
class PolicyStore() {
    private val file = File(AppPlatform.current.filesDir, "policy.json")

    @Synchronized
    fun load(): String? = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    @Synchronized
    fun save(json: String?) {
        if (json.isNullOrBlank()) return
        val tmp = File(file.parentFile, "policy.json.tmp")
        tmp.writeText(json)
        com.ygosandbox.app.platform.replaceFile(tmp, file)
    }

    /** How many decisions it has learned from (0 when nothing yet). */
    fun decisions(): Int = runCatching { JSONObject(load() ?: return 0).optInt("seen") }.getOrDefault(0)

    fun clear() { file.delete() }
}
