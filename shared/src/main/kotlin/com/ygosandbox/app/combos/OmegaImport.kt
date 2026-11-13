package com.ygosandbox.app.combos

import com.ygosandbox.app.model.DeckAnalysis
import java.security.MessageDigest

/**
 * YGO Omega replays -> a match for the Replays tab, and the first turn's line for the Combos tab
 * when the archetype went first. Same rule as for videos: a replay is left out as soon as the
 * archetype's player uses a card none of the result's lists play.
 */
object OmegaImport {

    sealed interface Result {
        data class Kept(val match: MatchReplay, val line: VideoCombo?) : Result
        data class Dropped(val id: String, val title: String, val reason: String) : Result
    }

    fun id(bytes: ByteArray): String =
        "omega:" + MessageDigest.getInstance("SHA-1").digest(bytes).take(10).joinToString("") { "%02x".format(it) }

    /**
     * When a replay was saved, from its name ("26_10_26-02_08_04": the date is year-month-day or
     * day-month-year; the reading closest to the file's last change wins), else the last change (ms).
     */
    fun savedAt(fileName: String, modified: Long): Long {
        val m = Regex("(\\d{2})_(\\d{2})_(\\d{2})-(\\d{2})_(\\d{2})").find(fileName) ?: return modified
        val (a, b, c, h, mi) = m.destructured.toList().map { it.toInt() }
        fun at(y: Int, mo: Int, d: Int) = runCatching {
            java.time.LocalDateTime.of(2000 + y, mo, d, h, mi).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
        val now = System.currentTimeMillis() + 86_400_000L
        val options = listOfNotNull(at(a, b, c), at(c, b, a)).filter { it <= now }
        if (options.isEmpty()) return modified
        return if (modified > 0) options.minByOrNull { kotlin.math.abs(it - modified) }!! else options.first()
    }

    fun convert(fileName: String, bytes: ByteArray, deck: DeckAnalysis, cn: CardNames, allowed: Set<Long>, handTraps: Set<Long>): Result {
        val id = id(bytes)
        val saved = savedAt(fileName, 0L).takeIf { it > 0 }?.let {
            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        }
        val date = saved?.toLocalDate()?.toString() ?: ""
        val time = saved?.toLocalTime()?.toString()?.take(5) ?: ""
        val title = "YGO Omega replay" + if (date.isNotEmpty()) " · $date $time" else " · $fileName"
        val g = try {
            OmegaReplay.read(bytes)
        } catch (e: Exception) {
            return Result.Dropped(id, title, e.message ?: "Couldn't read this replay")
        }
        fun c(x: Long) = cn.canonical(x)
        fun plays(p: Int) = g.seen[p].map(::c).filter { it !in handTraps }
        val inLists = (0..1).map { p -> plays(p).count { it in allowed } }
        val side = when {
            inLists[0] >= 3 && inLists[0] >= 2 * inLists[1] -> 0
            inLists[1] >= 3 && inLists[1] >= 2 * inLists[0] -> 1
            // a mirror: the recording player
            inLists[0] >= 3 && inLists[1] >= 3 -> g.deckOwner
            else -> return Result.Dropped(id, title, "Neither player plays ${deck.title}")
        }
        val other = 1 - side
        plays(side).firstOrNull { it !in allowed }?.let {
            return Result.Dropped(id, title, "The ${deck.title} player uses ${cn.name(it)}, which none of the deck lists play")
        }
        val opponent = plays(other).map { cn.archetype(it) }.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "Unknown deck"

        fun label(a: String) = a.replace('_', ' ').replaceFirstChar { it.uppercase() }
        val turns = g.turns.map { t ->
            val mine = t.events.filter { it.player == t.player && it.action != "search" && it.action != "send_to_gy" }
            val summary = mine.groupBy { it.action }.entries.joinToString(" · ") { (a, es) ->
                label(a) + ": " + es.joinToString(", ") { cn.name(it.code) }
            }
            TurnRecord(
                turn = t.number, ours = t.player == side, summary = summary,
                plays = mine.map { c(it.code) }.distinct(),
                endBoard = t.endField[t.player].map { c(it.first) },
                interrupts = t.events.filter { it.player != t.player && it.action == "activate" }.map { e ->
                    Interrupt(c(e.code), cn.name(e.code), e.player == side, if (e.chainedTo != 0L) cn.name(e.chainedTo) else "")
                },
            )
        }
        val how = when {
            g.winner == null -> "The replay stops before the end"
            g.winReason == 0 -> if (g.winner == side) "The opponent surrendered or ran out of time"
                else "The ${deck.title} player surrendered or ran out of time"
            g.winReason == 1 -> "Life Points reached 0"
            g.winReason == 2 -> "Deck out"
            else -> "Won by a card's effect"
        }
        val match = MatchReplay(
            url = id, title = title, uploader = "YGO Omega", date = date, format = "YGO Omega replay",
            opponent = opponent,
            games = listOf(
                GameRecord(
                    number = 1, weFirst = g.first == side, won = g.winner?.let { it == side }, how = how,
                    turns = turns, turningPoints = emptyList(),
                )
            ),
            sideChanges = emptyList(), lessons = emptyList(),
        )

        // Going first: turn 1 is a combo from the opening hand, with no opponent board. The
        // opponent's interruptions in it are noted; the engine replays the player's own steps.
        val first = g.turns.firstOrNull()
        val hand = g.openingHands[side].map(::c)
        val line = if (g.first == side && first != null && hand.size >= 5) {
            val steps = first.events.filter { it.player == side }.map { e ->
                ComboStep(null, e.action, c(e.code), cn.name(e.code), "")
            }
            val interrupts = first.events.filter { it.player != side && it.action == "activate" }.map { e ->
                cn.name(e.code) + if (e.chainedTo != 0L) " on ${cn.name(e.chainedTo)}" else ""
            }
            VideoCombo(
                url = id, title = title, uploader = "YGO Omega", date = date, going = "first",
                hand = hand, steps = steps,
                endBoard = first.endField[side].map { (code, where) -> BoardCard(c(code), cn.name(code), where) },
                interruptions = interrupts, chokePoints = emptyList(), uncertain = emptyList(),
            ).takeIf { steps.size >= 3 }
        } else null
        return Result.Kept(match, line)
    }
}
