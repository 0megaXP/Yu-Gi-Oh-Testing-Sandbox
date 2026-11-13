package com.ygosandbox.app.data

import com.ygosandbox.app.platform.AppPlatform

import com.ygoengine.ReferenceResult
import java.io.File
import java.security.MessageDigest

/**
 * Reference end boards, one file per card list (refs/<signature>.json). The same list saved
 * twice (a tournament list and its copy in My decks) shares one reference; editing a deck
 * gives it a new signature, so an old reference never describes a different list.
 */
class ReferenceStore() {
    private val dir = File(AppPlatform.current.filesDir, "refs").apply { mkdirs() }
    private val cache = HashMap<String, ReferenceResult?>()

    fun signature(main: List<Long>, extra: List<Long>): String {
        val text = main.sorted().joinToString(",") + "|" + extra.sorted().joinToString(",")
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    fun load(signature: String): ReferenceResult? = cache.getOrPut(signature) {
        val f = File(dir, "$signature.json")
        if (!f.exists()) null else runCatching { ReferenceResult.fromJson(f.readText()) }.getOrNull()
    }

    fun save(signature: String, result: ReferenceResult) {
        val tmp = File(dir, "$signature.json.tmp")
        tmp.writeText(result.json)
        tmp.renameTo(File(dir, "$signature.json"))
        cache[signature] = result
    }

    fun delete(signature: String) {
        File(dir, "$signature.json").delete()
        cache[signature] = null
    }
}
