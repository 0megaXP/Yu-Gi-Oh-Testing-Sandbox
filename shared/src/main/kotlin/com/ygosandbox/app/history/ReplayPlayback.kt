package com.ygosandbox.app.history

import com.ygoengine.DuelActivation
import com.ygoengine.DuelCard
import com.ygoengine.DuelMove
import com.ygoengine.DuelPlace
import com.ygoengine.DuelState
import com.ygoengine.PlayerState
import com.ygoengine.ZoneCard
import com.ygosandbox.app.combos.OmegaReplay
import com.ygosandbox.app.data.CardDatabase
import com.ygosandbox.app.play.LogEntry
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One action of a replay: the board after it, what it moved and activated, and its log lines. */
data class ReplayStep(
    val state: DuelState,
    val log: List<LogEntry>,
)

/**
 * Rebuilds a YGO Omega replay action by action from its duel messages (the saving player's view):
 * every card move, summon, Set, activation, attack, Life Point change and phase. Each step is a
 * full board, so the viewer can go forward and back freely. Cards the saving player couldn't see
 * (the opponent's hand, face-down cards) stay unknown.
 */
object ReplayPlayback {
    // ygopro message types, as Omega writes them
    private const val MSG_WIN = 5
    private const val MSG_START = 4
    private const val MSG_SHUFFLE_HAND = 33
    private const val MSG_NEW_TURN = 40
    private const val MSG_NEW_PHASE = 41
    private const val MSG_MOVE = 50
    private const val MSG_POS_CHANGE = 53
    private const val MSG_SET = 54
    private const val MSG_SWAP = 55
    private const val MSG_SUMMONING = 60
    private const val MSG_SPSUMMONING = 62
    private const val MSG_FLIPSUMMONING = 64
    private const val MSG_CHAINING = 70
    private const val MSG_CHAIN_SOLVED = 73
    private const val MSG_CHAIN_END = 74
    private const val MSG_DRAW = 90
    private const val MSG_DAMAGE = 91
    private const val MSG_RECOVER = 92
    private const val MSG_LPUPDATE = 94
    private const val MSG_PAY_LPCOST = 100
    private const val MSG_ATTACK = 110

    private const val LOC_DECK = 0x01
    private const val LOC_HAND = 0x02
    private const val LOC_MZONE = 0x04
    private const val LOC_SZONE = 0x08
    private const val LOC_GRAVE = 0x10
    private const val LOC_REMOVED = 0x20
    private const val LOC_EXTRA = 0x40
    private const val LOC_OVERLAY = 0x80

    private fun u8(m: ByteArray, o: Int): Int = if (o < m.size) m[o].toInt() and 0xff else 0
    private fun u16(m: ByteArray, o: Int): Int = u8(m, o) or (u8(m, o + 1) shl 8)
    private fun i32(m: ByteArray, o: Int): Int =
        if (o + 4 > m.size) 0 else ByteBuffer.wrap(m, o, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun code(m: ByteArray, o: Int): Long = i32(m, o).toLong() and 0x7fffffffL

    /** A card on the board while replaying: its code (0 = unknown to the viewer) and position bits. */
    private class Card(var code: Long, var pos: Int = 0x1, val materials: ArrayList<Long> = ArrayList())

    private class Side {
        var lp = 8000
        val deck = ArrayList<Card>()
        val hand = ArrayList<Card>()
        val extra = ArrayList<Card>()
        val grave = ArrayList<Card>()
        val banished = ArrayList<Card>()
        val monsters = arrayOfNulls<Card>(7)
        val spells = arrayOfNulls<Card>(8)
    }

    fun build(bytes: ByteArray, db: CardDatabase, names: List<String>): List<ReplayStep> {
        val raw = OmegaReplay.raw(bytes)
        fun name(c: Long) = if (c == 0L) "a card" else db.find(c)?.name ?: "#$c"
        fun isExtra(c: Long) = db.find(c)?.isExtraDeck == true

        val sides = listOf(Side(), Side())
        // Decks from the replay (main + extra), else 40 unknown cards and 15 in the Extra Deck.
        for (p in 0..1) {
            val list = raw.decks.getOrNull(p)?.first
            if (list != null && list.isNotEmpty()) {
                list.filter { !isExtra(it) }.forEach { sides[p].deck += Card(0) }
                list.filter { isExtra(it) }.forEach { sides[p].extra += Card(if (p == raw.owner) it else 0) }
            } else {
                repeat(40) { sides[p].deck += Card(0) }
                repeat(15) { sides[p].extra += Card(0) }
            }
        }

        var turn = 0
        var turnPlayer = 0
        var phase = "Opening hands"
        var ended = false
        var winner = -1
        val steps = ArrayList<ReplayStep>()
        var moves = ArrayList<DuelMove>()
        var acts = ArrayList<DuelActivation>()
        var log = ArrayList<LogEntry>()

        fun who(p: Int) = names.getOrElse(p) { "Player ${p + 1}" }

        fun list(s: Side, loc: Int): ArrayList<Card>? = when (loc) {
            LOC_DECK -> s.deck
            LOC_HAND -> s.hand
            LOC_EXTRA -> s.extra
            LOC_GRAVE -> s.grave
            LOC_REMOVED -> s.banished
            else -> null
        }

        fun snapshot(): DuelState {
            fun dc(c: Card) = DuelCard(c.code, if (c.code == 0L) "" else name(c.code))
            fun zc(c: Card?, monster: Boolean): ZoneCard? = c?.let {
                val card = if (it.code != 0L) db.find(it.code) else null
                val faceDown = it.pos and 0xA != 0
                ZoneCard(
                    code = if (faceDown && it.code == 0L) 0 else it.code,
                    name = if (it.code == 0L) "" else name(it.code),
                    position = when {
                        faceDown -> "set"
                        it.pos and 0x4 != 0 -> "def"
                        else -> "atk"
                    },
                    atk = card?.atk ?: 0, def = card?.def ?: 0,
                    materials = it.materials.size,
                    isMonster = monster,
                )
            }
            return DuelState(
                turn = turn, turnPlayer = turnPlayer, phase = phase, ended = ended, winner = winner,
                lastMove = log.lastOrNull()?.text.orEmpty(),
                players = sides.map { s ->
                    PlayerState(
                        lp = s.lp,
                        hand = s.hand.map(::dc),
                        deckCount = s.deck.size,
                        extra = s.extra.map(::dc),
                        graveyard = s.grave.map(::dc),
                        banished = s.banished.map(::dc),
                        monsters = s.monsters.map { zc(it, true) },
                        spells = s.spells.map { zc(it, false) },
                    )
                },
                decision = null,
                log = log.map { it.text },
                logPlayers = log.map { it.player },
                moves = moves,
                activations = acts,
            )
        }

        fun emit() {
            if (moves.isEmpty() && acts.isEmpty() && log.isEmpty()) return
            steps += ReplayStep(snapshot(), log)
            moves = ArrayList(); acts = ArrayList(); log = ArrayList()
        }

        fun say(p: Int, text: String) { log += LogEntry(p, text) }

        /** Takes the card at a place off the board (null when the place is empty or unknown). */
        fun take(p: Int, loc: Int, seq: Int, pos: Int, code: Long): Card? {
            val s = sides[p]
            if (loc and LOC_OVERLAY != 0) {
                val holder = s.monsters.getOrNull(seq) ?: return Card(code, pos)
                val i = holder.materials.indexOf(code).takeIf { it >= 0 } ?: pos.takeIf { it < holder.materials.size } ?: 0
                if (holder.materials.isNotEmpty()) holder.materials.removeAt(i)
                return Card(code)
            }
            return when (loc) {
                LOC_MZONE -> s.monsters.getOrNull(seq).also { if (seq in 0..6) s.monsters[seq] = null }
                LOC_SZONE -> s.spells.getOrNull(seq).also { if (seq in 0..7) s.spells[seq] = null }
                0 -> null
                else -> list(s, loc)?.let { l ->
                    when {
                        l.isEmpty() -> null
                        // the deck's top is its last card
                        loc == LOC_DECK && seq !in l.indices -> l.removeAt(l.size - 1)
                        seq in l.indices && (code == 0L || l[seq].code == 0L || l[seq].code == code) -> l.removeAt(seq)
                        else -> l.indexOfFirst { it.code == code }.takeIf { it >= 0 }?.let { l.removeAt(it) } ?: l.removeAt(l.size - 1)
                    }
                }
            }
        }

        fun put(p: Int, loc: Int, seq: Int, pos: Int, card: Card) {
            val s = sides[p]
            if (loc and LOC_OVERLAY != 0) {
                s.monsters.getOrNull(seq)?.materials?.add(card.code)
                return
            }
            card.pos = pos
            when (loc) {
                LOC_MZONE -> if (seq in 0..6) s.monsters[seq] = card
                LOC_SZONE -> if (seq in 0..7) s.spells[seq] = card
                0 -> {}
                else -> list(s, loc)?.let { l -> l.add(seq.coerceIn(0, l.size), card) }
            }
        }

        fun place(m: ByteArray, o: Int) = DuelPlace(u8(m, o) and 1, u8(m, o + 1), u8(m, o + 2), u8(m, o + 3))

        fun where(loc: Int) = when (loc and 0x7f) {
            LOC_DECK -> "the Deck"
            LOC_HAND -> "the hand"
            LOC_MZONE -> "the field"
            LOC_SZONE -> "the field"
            LOC_GRAVE -> "the GY"
            LOC_REMOVED -> "banishment"
            LOC_EXTRA -> "the Extra Deck"
            else -> ""
        }

        for (m in raw.messages) {
            if (m.isEmpty()) continue
            when (u8(m, 0)) {
                MSG_START -> if (m.size >= 18) {
                    val lp = listOf(i32(m, 2), i32(m, 6))
                    for (p in 0..1) if (lp[p] in 1..1_000_000) sides[p].lp = lp[p]
                    // deck sizes, when the decks themselves weren't in the replay
                    for (p in 0..1) if (raw.decks.getOrNull(p) == null) {
                        val d = u16(m, 10 + 4 * p); val e = u16(m, 12 + 4 * p)
                        if (d in 1..60) { sides[p].deck.clear(); repeat(d) { sides[p].deck += Card(0) } }
                        if (e in 0..15) { sides[p].extra.clear(); repeat(e) { sides[p].extra += Card(0) } }
                    }
                }
                MSG_NEW_TURN -> {
                    emit()
                    turn++
                    turnPlayer = u8(m, 1) and 1
                    phase = "Draw Phase"
                    say(turnPlayer, "Turn $turn: ${who(turnPlayer)}'s turn.")
                    emit()
                }
                MSG_NEW_PHASE -> {
                    emit()
                    phase = when (u16(m, 1)) {
                        0x01 -> "Draw Phase"; 0x02 -> "Standby Phase"; 0x04 -> "Main Phase 1"
                        0x08, 0x10, 0x20, 0x40, 0x80 -> "Battle Phase"; 0x100 -> "Main Phase 2"; 0x200 -> "End Phase"
                        else -> phase
                    }
                    say(-1, phase)
                    emit()
                }
                MSG_DRAW -> {
                    val p = u8(m, 1) and 1
                    val n = u8(m, 2)
                    val drawn = ArrayList<Long>()
                    repeat(n) { i ->
                        val c = code(m, 3 + 4 * i)
                        val card = take(p, LOC_DECK, -1, 0, 0) ?: Card(0)
                        card.code = c
                        moves += DuelMove(c, DuelPlace(p, LOC_DECK, 0, 0xA), DuelPlace(p, LOC_HAND, sides[p].hand.size, 0x1))
                        sides[p].hand += card
                        if (c != 0L) drawn += c
                    }
                    say(p, "${who(p)} draws ${if (n == 1) "a card" else "$n cards"}" +
                        if (drawn.isNotEmpty()) ": " + drawn.joinToString(", ") { name(it) } + "." else ".")
                    if (turn > 0) emit()
                }
                MSG_MOVE -> {
                    val c = code(m, 1)
                    val from = place(m, 5)
                    val to = place(m, 9)
                    val card = take(from.player, from.location, from.sequence, from.position, c) ?: Card(c)
                    if (c != 0L) card.code = c
                    put(to.player, to.location, to.sequence, to.position, card)
                    moves += DuelMove(c, from, to)
                    // say where known cards go, except the moves summons and activations already name
                    val toL = to.location and 0x7f
                    if (c != 0L && from.location != 0 && to.location and LOC_OVERLAY == 0 && toL != LOC_MZONE && toL != LOC_SZONE) {
                        val verb = when (toL) {
                            LOC_GRAVE -> "is sent to the GY"
                            LOC_REMOVED -> "is banished"
                            LOC_HAND -> "is added to the hand"
                            LOC_DECK -> "returns to the Deck"
                            LOC_EXTRA -> "returns to the Extra Deck"
                            else -> null
                        }
                        if (verb != null) say(to.player, "${name(c)} ${verb}" + (where(from.location).takeIf { it.isNotEmpty() }?.let { " from $it" } ?: "") + ".")
                    } else if (c != 0L && to.location and LOC_OVERLAY != 0) {
                        say(to.player, "${name(c)} becomes material.")
                    }
                }
                MSG_POS_CHANGE -> {
                    val p = u8(m, 5) and 1; val loc = u8(m, 6); val seq = u8(m, 7); val cur = u8(m, 9)
                    val card = if (loc == LOC_MZONE) sides[p].monsters.getOrNull(seq) else if (loc == LOC_SZONE) sides[p].spells.getOrNull(seq) else null
                    if (card != null) {
                        code(m, 1).takeIf { it != 0L }?.let { card.code = it }
                        card.pos = cur
                        say(p, "${name(card.code)} changes to ${if (cur and 0xA != 0) "face-down" else if (cur and 0x4 != 0) "Defense Position" else "Attack Position"}.")
                        emit()
                    }
                }
                MSG_SWAP -> {
                    val a = place(m, 5); val b = place(m, 13)
                    if (a.location == LOC_MZONE && b.location == LOC_MZONE && a.sequence in 0..6 && b.sequence in 0..6) {
                        val ca = sides[a.player].monsters[a.sequence]; val cb = sides[b.player].monsters[b.sequence]
                        sides[a.player].monsters[a.sequence] = cb; sides[b.player].monsters[b.sequence] = ca
                        moves += DuelMove(code(m, 1), a, b); moves += DuelMove(code(m, 9), b, a)
                        say(-1, "${name(code(m, 1))} and ${name(code(m, 9))} switch places.")
                        emit()
                    }
                }
                MSG_SHUFFLE_HAND -> {
                    val p = u8(m, 1) and 1
                    val n = u8(m, 2)
                    if (n == sides[p].hand.size) for (i in 0 until n) sides[p].hand[i].code = code(m, 3 + 4 * i)
                }
                MSG_SET -> {
                    val p = u8(m, 5) and 1
                    val c = code(m, 1)
                    say(p, "${who(p)} Sets ${if (c != 0L) name(c) else "a card"}.")
                    emit()
                }
                MSG_SUMMONING, MSG_SPSUMMONING, MSG_FLIPSUMMONING -> {
                    val c = code(m, 1); val p = u8(m, 5) and 1
                    val what = when (u8(m, 0)) { MSG_SUMMONING -> "Normal Summons"; MSG_FLIPSUMMONING -> "Flip Summons"; else -> "Special Summons" }
                    say(p, "${who(p)} $what ${name(c)}.")
                    emit()
                }
                MSG_CHAINING -> {
                    val c = code(m, 1)
                    val at = place(m, 5)
                    acts += DuelActivation(c, at, moves.size)
                    say(at.player, "${who(at.player)} activates ${name(c)}" + (where(at.location).takeIf { it.isNotEmpty() }?.let { " from $it" } ?: "") + ".")
                    emit()
                }
                MSG_CHAIN_SOLVED, MSG_CHAIN_END -> emit()
                MSG_ATTACK -> {
                    val a = place(m, 1); val t = place(m, 5)
                    val attacker = sides[a.player].monsters.getOrNull(a.sequence)?.code ?: 0
                    val target = if (t.location == LOC_MZONE) sides[t.player].monsters.getOrNull(t.sequence)?.code else null
                    say(a.player, "${name(attacker)} attacks " + (target?.let { if (it == 0L) "a face-down monster" else name(it) } ?: "directly") + ".")
                    emit()
                }
                MSG_DAMAGE, MSG_PAY_LPCOST -> {
                    val p = u8(m, 1) and 1; val v = i32(m, 2)
                    if (v in 1..1_000_000) {
                        sides[p].lp = (sides[p].lp - v).coerceAtLeast(0)
                        say(p, "${who(p)} ${if (u8(m, 0) == MSG_DAMAGE) "takes $v damage" else "pays $v LP"} (${sides[p].lp} LP).")
                        emit()
                    }
                }
                MSG_RECOVER -> {
                    val p = u8(m, 1) and 1; val v = i32(m, 2)
                    if (v in 1..1_000_000) {
                        sides[p].lp += v
                        say(p, "${who(p)} gains $v LP (${sides[p].lp} LP).")
                        emit()
                    }
                }
                MSG_LPUPDATE -> {
                    val p = u8(m, 1) and 1; val v = i32(m, 2)
                    if (v in 0..1_000_000) sides[p].lp = v
                }
                MSG_WIN -> {
                    emit()
                    val w = u8(m, 1)
                    ended = true
                    winner = if (w <= 1) w else 2
                    phase = "Duel over"
                    say(if (w <= 1) w else -1, if (w <= 1) "${who(w)} wins." else "The duel ends in a draw.")
                    emit()
                }
            }
        }
        emit()
        return steps
    }
}
