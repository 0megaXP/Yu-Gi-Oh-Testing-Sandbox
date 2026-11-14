package com.ygosandbox.app.combos

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.Inflater

/**
 * A YGO Omega replay file (.bytes, in Omega's "Replays" folder), read exactly.
 *
 * The file is raw-deflated BSON: the game settings, the players' decks (Omega's deck codes,
 * "Deck0" / "Deck1") and the duel's messages as the rules core sent them (the classic YGOPro
 * message layout): "GameMessages" as the recording player saw them, and, in replays of games
 * that were played, "SharedGameMessages" as the other player saw them. Each stream hides the
 * other player's draws and hand, so the two together give both players' full information:
 * both decks, both opening hands, every draw. Nothing is guessed.
 */
object OmegaReplay {

    data class Event(
        val player: Int,
        /** "activate", "normal_summon", "special_summon", "synchro_summon", "link_summon", ..., "set", "search", "send_to_gy". */
        val action: String,
        val code: Long,
        /** For an activation: the chain link it was chained to (0 when it started the chain). */
        val chainedTo: Long = 0,
        /**
         * The card is on the other player's field because [player] put it there (Angelechy Opposition,
         * Seneschal...): the event is [player]'s play, even though the rules core reports the other
         * player as the one summoning it or activating its effect.
         */
        val onOpponentField: Boolean = false,
        /**
         * For an activation, what it did for [player]: "combo" (it builds their board: summons,
         * searches, placements, or it only touches their own cards, like removing their own
         * Seneschal from the other field), "response" (it answers the opponent's chain link and
         * gains nothing), "removal" (it targets or removes the opponent's cards and gains nothing).
         * An effect that removes and also builds (Angelechy Enlisted) is "combo". Other events: "combo".
         */
        val role: String = "combo",
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
        /** The two players' Omega account ids (Player0, Player1), 0 when missing: the same pair in replays saved close together is one match. */
        val players: List<Long> = listOf(0L, 0L),
        /** GameSettings.Mode as Omega writes it. */
        val mode: Int = -1,
        /** Per player: their deck (main + extra, side), when the replay keeps it. */
        val decks: List<Pair<List<Long>, List<Long>>?> = listOf(null, null),
        /** Both players' hidden cards are known (the replay has both players' views). */
        val fullInfo: Boolean = false,
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
        val decks = (0..1).map { p -> (doc["Deck$p"] as? String)?.let { runCatching { deckCode(it) }.getOrNull() } }
        @Suppress("UNCHECKED_CAST")
        val shared = (doc["SharedGameMessages"] as? List<Any?>)?.filterIsInstance<ByteArray>().orEmpty()
        // whose view each stream is: the player whose draws it names
        val owner = viewer(msgs) ?: doc.keys.firstOrNull { it.startsWith("Deck") }?.removePrefix("Deck")?.toIntOrNull() ?: 0
        fun num(x: Any?) = (x as? Number)?.toLong() ?: 0L
        @Suppress("UNCHECKED_CAST")
        val settings = doc["GameSettings"] as? Map<String, Any?>
        val mine = messages(msgs, decks[owner], owner)
        val other = shared.takeIf { it.isNotEmpty() && viewer(it) == 1 - owner }?.let { messages(it, decks[owner], owner) }
        val game = if (other != null && other.turns.size == mine.turns.size) merge(mine, other, owner) else mine
        return game.copy(
            players = listOf(num(doc["Player0"]), num(doc["Player1"])),
            mode = settings?.get("Mode")?.let { num(it).toInt() } ?: -1,
            decks = decks,
        )
    }

    /** A replay's duel messages from the saving player's view, with both decks when kept (replay viewer). */
    data class Raw(val messages: List<ByteArray>, val owner: Int, val decks: List<Pair<List<Long>, List<Long>>?>)

    fun raw(bytes: ByteArray): Raw {
        val doc = try {
            Bson.read(inflate(bytes))
        } catch (e: Exception) {
            throw NotAReplay("Not a YGO Omega replay file")
        }
        @Suppress("UNCHECKED_CAST")
        val msgs = (doc["GameMessages"] as? List<Any?>)?.filterIsInstance<ByteArray>()
            ?: throw NotAReplay("Not a YGO Omega replay file (no duel messages)")
        val decks = (0..1).map { p -> (doc["Deck$p"] as? String)?.let { runCatching { deckCode(it) }.getOrNull() } }
        val owner = viewer(msgs) ?: doc.keys.firstOrNull { it.startsWith("Deck") }?.removePrefix("Deck")?.toIntOrNull() ?: 0
        return Raw(msgs, owner and 1, decks)
    }

    /** The player whose draws a message stream names (its viewer), or null when it can't tell. */
    private fun viewer(msgs: List<ByteArray>): Int? {
        val known = intArrayOf(0, 0)
        for (m in msgs) {
            if (m.isEmpty() || u8(m, 0) != MSG_DRAW) continue
            val p = u8(m, 1) and 1
            for (i in 0 until u8(m, 2)) if (code(m, 3 + 4 * i) != 0L) known[p]++
        }
        return when {
            known[0] > known[1] -> 0
            known[1] > known[0] -> 1
            else -> null
        }
    }

    /**
     * One game from both players' views: each player's own actions, hand, field and seen cards
     * come from the stream where their cards are named; the turns are the same in both.
     */
    private fun merge(a: Game, b: Game, viewerA: Int): Game {
        fun src(p: Int) = if (p == viewerA) a else b
        val turns = a.turns.indices.map { i ->
            val t = a.turns[i]
            val own = src(t.player).turns[i].events.filter { it.player == t.player }
            val others = src(1 - t.player).turns[i].events.filter { it.player != t.player }
            Turn(t.number, t.player, own + others, (0..1).map { p -> src(p).turns[i].endField[p] })
        }
        return a.copy(
            turns = turns,
            openingHands = (0..1).map { src(it).openingHands[it] },
            seen = (0..1).map { a.seen[it] + b.seen[it] },
            fullInfo = true,
        )
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
    private const val MSG_CHAIN_SOLVING = 72
    private const val MSG_CHAIN_SOLVED = 73
    private const val MSG_CHAIN_END = 74
    private const val MSG_BECOME_TARGET = 83
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
        // Per chain link: its event's index, who it belongs to, whether it answers the other
        // player, and what its resolution did (for the role).
        class Link(val event: Int, val who: Int, val answers: Boolean) {
            var gains = false      // built the player's board (summon, search, placement, add to hand)
            var hitsOpponent = false  // targeted / removed a card the other player owns
        }
        val links = ArrayList<Link>()
        var resolving: Link? = null
        // field slots (side, location, sequence) -> card, to name a chain link's targets
        val slots = HashMap<Triple<Int, Int, Int>, Long>()
        // the summon type of a card being Special Summoned, from the move that put it on the field
        val lastMoveReason = HashMap<Long, Long>()
        // Set cards come with no code; the move just before names the card for its owner
        val lastToSZone = longArrayOf(0, 0)
        // Cards a player put straight onto the other player's field (from their Extra Deck, Deck,
        // hand, GY or banishment), keyed by (field side, code): the player who put it there.
        // Control changes between fields (stealing) aren't counted: the taker plays the card.
        val given = HashMap<Pair<Int, Long>, Int>()
        // Who an event belongs to: the player who put the card on this field, else the one acting.
        fun by(p: Int, c: Long) = given[p to c] ?: p
        // Who owns a card on a field side: the player who put it there, else that side.
        fun owner(side: Int, c: Long) = given[side to c] ?: side

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
                    links.clear(); resolving = null
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
                    val fromSeq = u8(m, 7); val toSeq = u8(m, 11)
                    // what the resolving chain link did: built its player's board, or removed the other's card
                    resolving?.let { l ->
                        val fieldFrom = fromL and LOC_OVERLAY == 0 && (fromL == LOC_MZONE || fromL == LOC_SZONE)
                        val fieldTo = toL and LOC_OVERLAY == 0 && (toL == LOC_MZONE || toL == LOC_SZONE)
                        val ownerFrom = if (fieldFrom) owner(fromP, c) else fromP
                        if (fieldFrom && ownerFrom != l.who && !fieldTo) l.hitsOpponent = true
                        // a card of theirs onto any field (own or the opponent's) or into their hand
                        if (ownerFrom == l.who && (fieldTo || (toL == LOC_HAND && toP == l.who)) && !fieldFrom) l.gains = true
                        // or one of their monsters moved onto the other field (Angelechy Enlisted
                        // changing control after banishing: that sets up their next summon)
                        if (fieldFrom && fieldTo && fromP != toP && ownerFrom == l.who) l.gains = true
                    }
                    if (fromL and LOC_OVERLAY == 0 && (fromL == LOC_MZONE || fromL == LOC_SZONE)) slots.remove(Triple(fromP, fromL, fromSeq))
                    if (toL == LOC_SZONE || toL == LOC_MZONE) lastToSZone[toP] = c
                    val fromField = fromL and LOC_OVERLAY == 0 && (fromL == LOC_MZONE || fromL == LOC_SZONE)
                    val toField = toL and LOC_OVERLAY == 0 && (toL == LOC_MZONE || toL == LOC_SZONE)
                    if (fromField) {
                        val i = field[fromP].indexOfFirst { it.first == c }
                        if (i >= 0) field[fromP].removeAt(i)
                        if (!toField || toP != fromP) given.remove(fromP to c)
                    }
                    if (c != 0L && toField && !fromField && fromL != 0 && fromP != toP) given[toP to c] = fromP
                    if (toL and LOC_OVERLAY == 0 && (toL == LOC_MZONE || toL == LOC_SZONE) && c != 0L) {
                        field[toP] += c to zone(toL)
                        slots[Triple(toP, toL, toSeq)] = c
                    }
                    if (c != 0L && turnNo > 0) {
                        see(by(toP, c), c)
                        val p = toP
                        // effects that bring cards out of the Deck
                        if (fromL == LOC_DECK && toL == LOC_HAND && why and 0x40L != 0L) events += Event(p, "search", c)
                        else if (fromL == LOC_DECK && toL == LOC_GRAVE) events += Event(p, "send_to_gy", c)
                    }
                }
                MSG_SUMMONING, MSG_FLIPSUMMONING -> {
                    val c = code(m, 1); val p = u8(m, 5) and 1
                    resolving?.let { if (by(p, c) == it.who) it.gains = true }
                    see(by(p, c), c)
                    events += Event(by(p, c), if (u8(m, 0) == MSG_SUMMONING) "normal_summon" else "flip_summon", c,
                        onOpponentField = by(p, c) != p)
                }
                MSG_SPSUMMONING -> {
                    val c = code(m, 1); val p = u8(m, 5) and 1
                    resolving?.let { if (by(p, c) == it.who) it.gains = true }
                    see(by(p, c), c)
                    val r = lastMoveReason[c] ?: REASON_SPSUMMON
                    val action = when {
                        r and REASON_LINK != 0L -> "link_summon"
                        r and REASON_XYZ != 0L -> "xyz_summon"
                        r and REASON_SYNCHRO != 0L -> "synchro_summon"
                        r and REASON_FUSION != 0L -> "fusion_summon"
                        r and REASON_RITUAL != 0L -> "ritual_summon"
                        else -> "special_summon"
                    }
                    events += Event(by(p, c), action, c, onOpponentField = by(p, c) != p)
                }
                MSG_SET -> {
                    val p = u8(m, 5) and 1
                    val c = code(m, 1).takeIf { it != 0L } ?: lastToSZone[p]
                    if (c != 0L) { see(by(p, c), c); events += Event(by(p, c), "set", c, onOpponentField = by(p, c) != p) }
                }
                MSG_CHAINING -> {
                    val c = code(m, 1); val p = u8(m, 5) and 1
                    // the controller's location (MSG_CHAINING: controller, location, ...)
                    val onField = u8(m, 6).let { it and LOC_OVERLAY == 0 && (it == LOC_MZONE || it == LOC_SZONE) }
                    val who = if (onField) by(p, c) else p
                    see(who, c)
                    val answers = links.lastOrNull()?.let { it.who != who } ?: false
                    events += Event(who, "activate", c, chain.lastOrNull() ?: 0, onOpponentField = who != p)
                    links += Link(events.size - 1, who, answers)
                    chain += c
                }
                MSG_BECOME_TARGET -> {
                    // the targets of the link just activated
                    val l = links.lastOrNull()
                    if (l != null && resolving == null) for (i in 0 until u8(m, 1)) {
                        val o = 2 + 4 * i
                        if (o + 3 >= m.size) break
                        val side = u8(m, o) and 1; val loc = u8(m, o + 1); val seq = u8(m, o + 2)
                        val c = slots[Triple(side, loc, seq)] ?: 0L
                        if ((loc == LOC_MZONE || loc == LOC_SZONE) && (if (c != 0L) owner(side, c) else side) != l.who) l.hitsOpponent = true
                    }
                }
                MSG_CHAIN_SOLVING -> resolving = links.getOrNull(u8(m, 1) - 1)
                MSG_CHAIN_SOLVED -> resolving = null
                MSG_CHAIN_END -> {
                    // each link's role, now that its resolution is known
                    for (l in links) {
                        val e = events.getOrNull(l.event) ?: continue
                        val role = when {
                            l.gains -> "combo"
                            // answering their link, or acting in their turn without building anything
                            // (Droll & Lock Bird after a search starts its own chain)
                            l.answers || l.who != turnPlayer -> "response"
                            l.hitsOpponent -> "removal"
                            else -> "combo"
                        }
                        if (role != e.role) events[l.event] = e.copy(role = role)
                    }
                    links.clear(); resolving = null
                    chain.clear()
                }
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
