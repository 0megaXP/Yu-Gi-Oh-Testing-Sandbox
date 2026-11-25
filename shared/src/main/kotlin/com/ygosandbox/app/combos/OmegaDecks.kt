package com.ygosandbox.app.combos

import com.ygosandbox.app.data.CardDatabase
import com.ygosandbox.app.model.SourceDeck
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Decks from YGO Omega, to import into My decks:
 * - Player.data (in Omega's install folder, YGO Omega_Data\Files, with copies in Files\Backup):
 *   every deck the player saved, as raw-deflated BSON {DeckList: {name: {MD, ED, SD}}};
 * - .ydk files (what Omega exports and imports);
 * - Omega deck codes (base64 of raw-deflated counts and passcodes), pasted.
 */
object OmegaDecks {
    /** Reads one picked file; [name] decides how. Unknown files give no decks. */
    fun readFile(name: String, bytes: ByteArray, db: CardDatabase): List<SourceDeck> = when {
        name.endsWith(".ydk", ignoreCase = true) -> listOfNotNull(readYdk(name.substringBeforeLast('.'), String(bytes, Charsets.UTF_8)))
        name.endsWith(".txt", ignoreCase = true) -> listOfNotNull(readCode(name.substringBeforeLast('.'), String(bytes, Charsets.UTF_8), db))
        else -> readPlayerData(bytes)
    }

    /** Every deck in an Omega Player.data (or one of its backups). */
    fun readPlayerData(bytes: ByteArray): List<SourceDeck> {
        val doc = runCatching { OmegaReplay.Bson.read(inflateRaw(bytes)) }.getOrNull()
            ?: runCatching { OmegaReplay.Bson.read(bytes) }.getOrNull() // in case a version stores it uncompressed
            ?: throw IllegalArgumentException("not an Omega deck file")
        @Suppress("UNCHECKED_CAST")
        val list = doc["DeckList"] as? Map<String, Any?> ?: throw IllegalArgumentException("no deck list inside")
        return list.mapNotNull { (rawName, parts) ->
            @Suppress("UNCHECKED_CAST")
            val p = parts as? Map<String, Any?> ?: return@mapNotNull null
            fun ids(key: String) = (p[key] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toLong() }.filter { it > 0 }
            val deck = deck(rawName.trim(), ids("MD"), ids("ED"), ids("SD"))
            deck.takeIf { it.hasList }
        }
    }

    /** A .ydk file: #main, #extra and !side sections of passcodes. */
    fun readYdk(name: String, text: String): SourceDeck? {
        val parts = mapOf("main" to ArrayList<Long>(), "extra" to ArrayList(), "side" to ArrayList())
        var part = "main"
        text.lineSequence().forEach { raw ->
            val l = raw.trim()
            when {
                l.equals("#main", true) -> part = "main"
                l.equals("#extra", true) -> part = "extra"
                l.equals("!side", true) -> part = "side"
                else -> l.toLongOrNull()?.takeIf { it > 0 }?.let { parts.getValue(part).add(it) }
            }
        }
        return deck(name, parts.getValue("main"), parts.getValue("extra"), parts.getValue("side")).takeIf { it.hasList }
    }

    /** An Omega deck code. Main and Extra Deck cards come mixed in it, so the card database sorts them. */
    fun readCode(name: String, code: String, db: CardDatabase): SourceDeck? {
        val (mixed, side) = runCatching { OmegaReplay.deckCode(code.trim().filterNot { it.isWhitespace() }) }.getOrNull() ?: return null
        val (extra, main) = mixed.filter { it > 0 }.partition { db.find(it)?.isExtraDeck == true }
        return deck(name.ifBlank { "Omega deck" }, main, extra, side.filter { it > 0 }).takeIf { it.hasList }
    }

    // ---- export (YGO Sandbox -> YGO Omega)

    /** A deck as an Omega deck code: raw-deflated [Main+Extra count][Side count] then 4-byte passcodes. */
    fun writeCode(main: List<Long>, extra: List<Long>, side: List<Long>): String {
        val mixed = main + extra
        require(mixed.size <= 255 && side.size <= 255) { "too many cards" }
        val raw = ByteBuffer.allocate(2 + 4 * (mixed.size + side.size)).order(ByteOrder.LITTLE_ENDIAN)
        raw.put(mixed.size.toByte()).put(side.size.toByte())
        (mixed + side).forEach { raw.putInt(it.toInt()) }
        val def = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            def.setInput(raw.array())
            def.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!def.finished()) out.write(buf, 0, def.deflate(buf))
            return Base64.getEncoder().encodeToString(out.toByteArray())
        } finally {
            def.end()
        }
    }

    /** A deck as a .ydk file, which YGO Omega (and other simulators) import. */
    fun writeYdk(main: List<Long>, extra: List<Long>, side: List<Long>): String = buildString {
        append("#created by YGO Sandbox\n#main\n")
        main.forEach { append(it).append('\n') }
        append("#extra\n")
        extra.forEach { append(it).append('\n') }
        append("!side\n")
        side.forEach { append(it).append('\n') }
    }

    private fun deck(name: String, main: List<Long>, extra: List<Long>, side: List<Long>) =
        SourceDeck(name, "", "YGO Omega", "", "", "", true, main, extra, side)

    private fun inflateRaw(data: ByteArray): ByteArray {
        val inf = Inflater(true)
        try {
            inf.setInput(data)
            val out = ByteArrayOutputStream(data.size * 4)
            val buf = ByteArray(64 * 1024)
            while (!inf.finished()) {
                val k = inf.inflate(buf)
                if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, k)
                if (out.size() > 64 * 1024 * 1024) throw IllegalArgumentException("too large")
            }
            if (out.size() == 0) throw IllegalArgumentException("empty")
            return out.toByteArray()
        } finally {
            inf.end()
        }
    }
}
