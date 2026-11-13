package com.ygosandbox.app.combos

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.Inflater

/**
 * A YGO Omega replay file (.bytes, in Omega's "Replays" folder), read exactly.
 *
 * The file is raw-deflated BSON: the game settings, the recording player's deck (Omega's deck
 * code) and the duel's messages as the rules core sent them to that player (the classic YGOPro
 * message layout). Nothing is guessed: every activation, summon, move and draw is there with
 * its card's passcode. The opponent's hidden cards (their draws, Set cards) have no code.
 */
object OmegaReplay {

    data class Event(
        val player: Int,
        /** "activate", "normal_summon", "special_summon", "synchro_summon", "link_summon", ..., "set", "search", "send_to_gy". */
        val action: String,
        val code: Long,
        /** For an activation: the chain link it was chained to (0 when it started the chain). */
        val chainedTo: Long = 0,
    )

    data class Turn(
        val number: Int,
        val player: Int,
        val events: List<Event>,
        /** Per player: the cards on the field at the end of this turn (known codes only), with "Monster Zone" / "Spell & Trap Zone". */
        val endField: List<List<Pair<Long, String>>>,
    )

    data class Game(
        /** The player who took the first turn (0 or 1). */
        val first: Int,
        /** The winner (0 or 1), or null when the replay stops before the end. */
        val winner: Int?,
        val winReason: Int,
        val turns: List<Turn>,
        /** Per player: the opening hand (known codes; empty when hidden). */
        val openingHands: List<List<Long>>,
        /** Per player: every card code seen played or revealed. */
        val seen: List<Set<Long>>,
        /** The recording player's deck: main + extra, and side. Null when the replay doesn't keep decks. */
        val deck: Pair<List<Long>, List<Long>>?,
        /** Which player [deck] belongs to. */
        val deckOwner: Int,
    )

    class NotAReplay(msg: String) : Exception(msg)

    fun read(bytes: ByteArray): Game {
        val doc = try {
            Bson.read(inflate(bytes))
        } catch (e: Exception) {
            throw NotAReplay("Not a YGO Omega replay file")
        }
        @Suppress("UNCHECKED_CAST")
        val msgs = (doc["GameMessages"] as? List<Any?>)?.filterIsInstance<ByteArray>()
            ?: throw NotAReplay("Not a YGO Omega replay file (no duel messages)")
        val deckKey = doc.keys.firstOrNull { it.startsWith("Deck") }
        val deck = (doc[deckKey] as? String)?.let { runCatching { deckCode(it) }.getOrNull() }
        val owner = deckKey?.removePrefix("Deck")?.toIntOrNull() ?: 0
        return messages(msgs, deck, owner)
    }

    /** An Omega deck code: raw-deflated [main+extra count][side count] then 4-byte passcodes. */
    fun deckCode(code: String): Pair<List<Long>, List<Long>> {
        val raw = inflate(Base64.getDecoder().decode(code.trim()))
        val n = raw[0].toInt() and 0xff
        val s = raw[1].toInt() and 0xff
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val codes = (0 until n + s).map { (b.getInt(2 + 4 * it).toLong() and 0xffffffffL) }
        return codes.take(n) to codes.drop(n)
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater(true)
        inf.setInput(data)
        val out = ByteArrayOutputStream(data.size * 8)
        val buf = ByteArray(64 * 1024)
        while (!inf.finished()) {
            val k = inf.inflate(buf)
            if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, k)
        }
        inf.end()
        if (out.size() == 0) throw NotAReplay("empty")
        return out.toByteArray()
    }

    // ------------------------------------------------------------ messages

    private const val MSG_WIN = 5
    private const val MSG_NEW_TURN = 40
    private const val MSG_MOVE = 50
    private const val MSG_SET = 54
    private const val MSG_SUMMONING = 60
    private const val MSG_SPSUMMONING = 62
    private const val MSG_FLIPSUMMONING = 64
    private const val MSG_CHAINING = 70
    private const val MSG_CHAIN_END = 74
    private const val MSG_CONFIRM_CARDS = 31
    private const val MSG_DRAW = 90

    private const val LOC_DECK = 0x01
    private const val LOC_HAND = 0x02
    private const val LOC_MZONE = 0x04
    private const val LOC_SZONE = 0x08
    private const val LOC_GRAVE = 0x10
    private const val LOC_EXTRA = 0x40
    private const val LOC_OVERLAY = 0x80

    private const val REASON_SPSUMMON = 0x800L
    private const val REASON_FUSION = 0x40000L
    private const val REASON_SYNCHRO = 0x80000L
    private const val REASON_RITUAL = 0x100000L
    private const val REASON_XYZ = 0x200000L
    private const val REASON_LINK = 0x10000000L

    private fun u32(m: ByteArray, o: Int): Long =
        if (o + 4 > m.size) 0 else ByteBuffer.wrap(m, o, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    private fun u8(m: ByteArray, o: Int): Int = if (o < m.size) m[o].toInt() and 0xff else 0
    private fun code(m: ByteArray, o: Int): Long = u32(m, o) and 0x7fffffffL

    private fun messages(msgs: List<ByteArray>, deck: Pair<List<Long>, List<Long>>?, owner: Int): Game {
        val turns = ArrayList<Turn>()
        var events = ArrayList<Event>()
        var turnNo = 0
        var turnPlayer = -1
        var first = -1
        var winner: Int? = null
        var reason = 0
        val field = listOf(ArrayList<Pair<Long, String>>(), ArrayList())
        val opening = listOf(ArrayList<Long>(), ArrayList())
        val seen = listOf(HashSet<Long>(), HashSet())
        val chain = ArrayList<Long>()
        // the summon type of a card being Special Summoned, from the move that put it on the field
        val lastMoveReason = HashMap<Long, Long>()
        // Set cards come with no code; the move just before names the card for its owner
        val lastToSZone = longArrayOf(0, 0)

        fun zone(loc: Int) = if (loc and 0x7f == LOC_MZONE) "Monster Zone" else "Spell & Trap Zone"
        fun snapshot() = field.map { it.toList() }
        fun closeTurn() {
            if (turnPlayer >= 0) turns += Turn(turnNo, turnPlayer, events, snapshot())
            events = ArrayList()
        }
        fun see(p: Int, c: Long) { if (c != 0L && p in 0..1) seen[p] += c }

        for (m in msgs) {
            if (m.isEmpty()) continue
            when (u8(m, 0)) {
                MSG_NEW_TURN -> {
                    closeTurn()
                    turnPlayer = u8(m, 1) and 1
                    if (first < 0) first = turnPlayer
                    turnNo++
                    chain.clear()
                }
                MSG_DRAW -> {
                    val p = u8(m, 1) and 1
                    val n = u8(m, 2)
                    // the opening hand: the draws before the first turn
                    if (turnNo == 0) for (i in 0 until n) code(m, 3 + 4 * i).takeIf { it != 0L }?.let { opening[p] += it; see(p, it) }
                }
                MSG_MOVE -> {
                    val c = code(m, 1)
                    val fromP = u8(m, 5) and 1; val fromL = u8(m, 6)
                    val toP = u8(m, 9) and 1; val toL = u8(m, 10)
                    val why = u32(m, 13)
                    if (c != 0L) lastMoveReason[c] = why
                    if (toL == LOC_SZONE || toL == LOC_MZONE) lastToSZone[toP] = c
                    if (fromL and LOC_OVERLAY == 0 && (fromL == LOC_MZONE || fromL == LOC_SZONE)) {
                        val i = field[fromP].indexOfFirst { it.first == c }
                        if (i >= 0) field[fromP].removeAt(i)
                    }
                    if (toL and LOC_OVERLAY == 0 && (toL == LOC_MZONE || toL == LOC_SZONE) && c != 0L) field[toP] += c to zone(toL)
                    if (c != 0L && turnNo > 0) {
                        see(toP, c)
                        val p = toP
                        // effects that bring cards out of the Deck
                        if (fromL == LOC_DECK && toL == LOC_HAND && why and 0x40L != 0L) events += Event(p, "search", c)
                        else if (fromL == LOC_DECK && toL == LOC_GRAVE) events += Event(p, "send_to_gy", c)
                    }
                }
                MSG_SUMMONING, MSG_FLIPSUMMONING -> {
                    val c = code(m, 1); val p = u8(m, 5) and 1
                    see(p, c)
                    events += Event(p, if (u8(m, 0) == MSG_SUMMONING) "normal_summon" else "flip_summon", c)
                }
                MSG_SPSUMMONING -> {
                    val c = code(m, 1); val p = u8(m, 5) and 1
                    see(p, c)
                    val r = lastMoveReason[c] ?: REASON_SPSUMMON
                    val action = when {
                        r and REASON_LINK != 0L -> "link_summon"
                        r and REASON_XYZ != 0L -> "xyz_summon"
                        r and REASON_SYNCHRO != 0L -> "synchro_summon"
                        r and REASON_FUSION != 0L -> "fusion_summon"
                        r and REASON_RITUAL != 0L -> "ritual_summon"
                        else -> "special_summon"
                    }
                    events += Event(p, action, c)
                }
                MSG_SET -> {
                    val p = u8(m, 5) and 1
                    val c = code(m, 1).takeIf { it != 0L } ?: lastToSZone[p]
                    if (c != 0L) { see(p, c); events += Event(p, "set", c) }
                }
                MSG_CHAINING -> {
                    val c = code(m, 1); val p = u8(m, 5) and 1
                    see(p, c)
                    events += Event(p, "activate", c, chain.lastOrNull() ?: 0)
                    chain += c
                }
                MSG_CHAIN_END -> chain.clear()
                MSG_CONFIRM_CARDS -> {
                    val p = u8(m, 1) and 1
                    for (i in 0 until u8(m, 2)) see(p, code(m, 3 + 8 * i))
                }
                MSG_WIN -> {
                    winner = u8(m, 1).takeIf { it <= 1 }
                    reason = u8(m, 2)
                }
            }
        }
        closeTurn()
        return Game(
            first = first.coerceAtLeast(0), winner = winner, winReason = reason, turns = turns,
            openingHands = opening, seen = seen, deck = deck, deckOwner = owner and 1,
        )
    }

    // ------------------------------------------------------------ BSON (only what Omega writes)

    internal object Bson {
        fun read(b: ByteArray): Map<String, Any?> = doc(ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN))

        private fun cstring(b: ByteBuffer): String {
            val start = b.position()
            while (b.get() != 0.toByte()) { }
            return String(b.array(), start, b.position() - start - 1, Charsets.UTF_8)
        }

        private fun doc(b: ByteBuffer): Map<String, Any?> {
            val start = b.position()
            val size = b.int
            val end = start + size
            val out = LinkedHashMap<String, Any?>()
            while (b.position() < end - 1) {
                val type = b.get().toInt() and 0xff
                if (type == 0) break
                val key = cstring(b)
                out[key] = when (type) {
                    0x01 -> b.double
                    0x02 -> { val n = b.int; String(b.array(), b.position(), n - 1, Charsets.UTF_8).also { b.position(b.position() + n) } }
                    0x03 -> doc(b)
                    0x04 -> doc(b).values.toList()
                    0x05 -> { val n = b.int; b.get(); ByteArray(n).also { b.get(it) } }
                    0x07 -> { b.position(b.position() + 12); null }
                    0x08 -> b.get() != 0.toByte()
                    0x09 -> b.long
                    0x0A -> null
                    0x10 -> b.int
                    0x11, 0x12 -> b.long
                    else -> throw IllegalStateException("BSON type $type")
                }
            }
            b.position(end)
            return out
        }
    }
}
