package com.ygosandbox.app.combos

import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Reads a video for free, on the phone, with text recognition (ML Kit, offline).
 *
 * Two kinds of text are used:
 * - **A duel log** (Duelingbook, Dueling Nexus and others show the actions as text): a line with
 *   an action word and a card name ("Special Summoned "X"", "Activated X") gives the action,
 *   and a "Player: …" prefix gives who did it. Turn and win lines mark turns and games.
 * - **Card names alone** (activation banners, card panels): the cards in the order they appear,
 *   as "use" steps, when the video has no log.
 * A line counts when it appears, not while it stays on screen.
 */
object FreeReader {

    /** One thing read off the screen. [action] is a video-reader action, "use", "turn" or "game_end". */
    data class Event(val t: Double, val code: Long?, val action: String, val player: String?, val exact: Boolean, val text: String)

    class Result(
        /** Everything read, in order. */
        val events: List<Event>,
        /** The video has a duel log (actions and players come from it). */
        val log: Boolean,
        val hand: List<Long>,
    ) {
        /** The cards played, in order (combo steps). */
        val cards: List<Event> get() = events.filter { it.code != null && it.action !in setOf("turn", "game_end", "attack", "draw") }
        val players: List<String> get() = events.mapNotNull { it.player }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.take(2).map { it.key }
    }

    // Action words, most specific first.
    private val VERBS = listOf(
        "link summon" to "link_summon", "xyz summon" to "xyz_summon", "synchro summon" to "synchro_summon",
        "fusion summon" to "fusion_summon", "ritual summon" to "ritual_summon", "pendulum summon" to "pendulum_summon",
        "normal summon" to "normal_summon", "tribute summon" to "normal_summon", "special summon" to "special_summon",
        "activat" to "activate", "banish" to "banish", "sent" to "send_to_gy", "to gy" to "send_to_gy",
        "to the gy" to "send_to_gy", "graveyard" to "send_to_gy", "discard" to "send_to_gy", "added" to "search",
        "to hand" to "search", "to the hand" to "search", "attach" to "attach", "detach" to "send_to_gy",
        "overlay" to "attach", "attack" to "attack", "drew" to "draw", "drawn" to "draw",
    )
    private val SET = Regex("""\bset\b""")
    private val PLAYER = Regex("""^([^:"“”]{2,24}):\s*(.+)$""")
    private val QUOTED = Regex("""["“”']([^"“”]{3,70})["“”']""")
    private val TURN = Regex("""\bturn\s*(\d{1,2})\b""")
    private val END = listOf("won the duel", "wins the duel", "won the match", "wins the match", "surrender", "admitted defeat",
        "lost the duel", "has won", " wins", " won")

    private fun actionOf(lower: String): String? =
        VERBS.firstOrNull { it.first in lower }?.second ?: if (SET.containsMatchIn(lower)) "set" else null

    suspend fun read(
        frames: List<Frame>,
        cn: CardNames,
        allowed: Set<Long>,
        handTraps: Set<Long>,
        title: String,
        description: String,
        extraDeck: Set<Long>,
        onProgress: (Float) -> Unit,
    ): Result = withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val pool = allowed + handTraps
        val logEvents = ArrayList<Event>()
        val nameEvents = ArrayList<Event>()
        var logFrames = 0
        var previousLines = emptyMap<String, Int>()
        var previousNames = emptySet<Long>()
        try {
            frames.forEachIndexed { i, f ->
                coroutineContext.ensureActive()
                onProgress(i.toFloat() / frames.size)
                val bmp = BitmapFactory.decodeFile(f.file.absolutePath) ?: return@forEachIndexed
                val text = runCatching { Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0))) }.getOrNull()
                bmp.recycle()
                text ?: return@forEachIndexed
                // lines top to bottom: a log grows downwards, so new lines come in reading order
                val lines = text.textBlocks.flatMap { it.lines }.sortedBy { it.boundingBox?.top ?: 0 }.map { it.text.trim() }

                // --- duel log lines: new ones only (a line staying on screen is the same action)
                val counts = lines.groupingBy { it }.eachCount()
                var logHere = 0
                val seenNow = HashMap<String, Int>()
                for (raw in lines) {
                    val k = seenNow.merge(raw, 1, Int::plus)!!
                    val parsed = parseLogLine(raw, cn, pool) ?: continue
                    logHere++
                    if (k <= (previousLines[raw] ?: 0)) continue  // was already there
                    logEvents += parsed.copy(t = f.seconds)
                }
                if (logHere >= 2) logFrames++
                previousLines = counts

                // --- card names alone (banners, panels), also wrapped over two lines
                val found = LinkedHashSet<Long>()
                for (c in lines + lines.zipWithNext { a, b -> "$a $b" }) {
                    if (c.length < 5) continue
                    cn.match(c, pool, 0.85)?.let { found += cn.canonical(it) }
                }
                for (id in found) {
                    if (id in previousNames) continue
                    val last = nameEvents.lastOrNull()
                    if (last != null && last.code == id && f.seconds - last.t < 4) continue
                    nameEvents += Event(f.seconds, id, "use", null, false, cn.name(id))
                }
                previousNames = found
            }
        } finally {
            recognizer.close()
        }
        // A video with a log: its lines are the record (names in card panels would repeat them).
        val log = logFrames >= 5 && logEvents.count { it.code != null } >= 3
        val events = if (log) logEvents else nameEvents.filter { it.code !in handTraps }

        // The opening hand: cards the title or description names, else the first Main Deck cards played.
        val named = (title.split(Regex("[,/+:|\\-–]| and | with ")) + description.lines().take(30))
            .mapNotNull { s -> s.trim().takeIf { it.length >= 5 }?.let { cn.match(it, allowed, 0.9) } }
            .map { cn.canonical(it) }.filter { it !in extraDeck }.distinct()
        val firstPlayed = events.mapNotNull { it.code }.filter { it in allowed && it !in extraDeck && it !in handTraps }.distinct().take(3)
        Result(events, log, (named + firstPlayed).distinct().take(5))
    }

    /** A duel log line: an action with a card, a turn line or a win line; null for anything else. */
    private fun parseLogLine(raw: String, cn: CardNames, pool: Set<Long>): Event? {
        var player: String? = null
        var body = raw
        PLAYER.find(raw)?.let { m -> player = m.groupValues[1].trim(); body = m.groupValues[2] }
        val lower = body.lowercase()
        TURN.find(lower)?.let { m ->
            if (lower.length < 40) return Event(0.0, null, "turn", player, true, "Turn ${m.groupValues[1]}")
        }
        if (END.any { it in lower } && lower.length < 80) return Event(0.0, null, "game_end", player ?: body.substringBefore(' '), true, body)
        val action = actionOf(lower) ?: return null
        // a quoted name is exact and may be any card (the opponent's too); otherwise only this deck's cards
        val quoted = QUOTED.findAll(body).map { it.groupValues[1] }.toList()
        for (q in quoted) {
            cn.exact(q)?.let { return Event(0.0, cn.canonical(it), action, player, true, raw) }
            cn.match(q, pool, 0.85)?.let { return Event(0.0, cn.canonical(it), action, player, false, raw) }
        }
        val id = cn.match(body, pool, 0.85) ?: return null
        return Event(0.0, cn.canonical(id), action, player, false, raw)
    }
}
