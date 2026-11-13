package com.ygosandbox.app.combos

import com.ygosandbox.app.platform.AppPlatform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ygosandbox.app.data.CardDatabase
import com.ygosandbox.app.model.DeckAnalysis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.ygosandbox.app.work.BackgroundWork
import java.io.File

data class ReplaysUi(
    val libraries: Map<String, ReplayLibrary> = emptyMap(),
    val searchFor: String? = null,
    val searching: Boolean = false,
    val hits: List<VideoHit> = emptyList(),
    val searchError: String? = null,
    val runFor: String? = null,
    val run: RunProgress? = null,
    val runNote: String? = null,
)

/**
 * Finds match videos (feature matches, tournament rounds, ranked replays) for an archetype and
 * reads each into a turn-by-turn report for both players, seen from the side playing the
 * archetype. Like combo videos, a match is left out as soon as that side plays a card none of
 * the result's tournament lists play.
 */
class ReplayViewModel : ViewModel() {
    val settings = ComboSettings()
    private val store = ReplayStore()
    // process-wide: a read keeps running (and showing its progress) when the screen is left
    private val _ui = shared
    val ui: StateFlow<ReplaysUi> = _ui

    fun library(deckId: String): ReplayLibrary {
        _ui.value.libraries[deckId]?.let { return it }
        val lib = store.load(deckId)
        _ui.update { it.copy(libraries = it.libraries + (deckId to lib)) }
        return lib
    }

    private fun save(lib: ReplayLibrary) {
        val l = lib.copy(updatedAt = System.currentTimeMillis())
        store.save(l)
        _ui.update { it.copy(libraries = it.libraries + (l.deckId to l)) }
    }

    fun search(deck: DeckAnalysis, since: String?) {
        if (job?.isActive == true) return
        _ui.update { it.copy(searchFor = deck.id, searching = true, hits = emptyList(), searchError = null) }
        job = viewModelScope.launch {
            try {
                val hits = VideoSource.search(deck.archetypes.joinToString(" "), since, kind = VideoSource.Kind.REPLAY)
                _ui.update { it.copy(hits = hits, searchError = if (hits.isEmpty()) "No match videos found for this archetype in that period." else null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(searchError = "YouTube search failed: ${e.message ?: e.javaClass.simpleName}. " +
                    "If this keeps happening, YouTube changed something and the app needs an update.") }
            } finally {
                _ui.update { it.copy(searching = false) }
            }
        }
    }

    fun clearSearch() = _ui.update { it.copy(searchFor = null, hits = emptyList(), searchError = null) }
    fun stop() { job?.cancel() }

    fun forget(deckId: String, url: String) {
        val lib = library(deckId)
        save(lib.copy(matches = lib.matches.filter { it.url != url }, dropped = lib.dropped.filter { it.url != url }))
    }

    fun read(deck: DeckAnalysis, db: CardDatabase, videos: List<VideoHit>) {
        if (job?.isActive == true || videos.isEmpty()) return
        val key = settings.apiKey
        if (!settings.freeReading && key.isBlank()) {
            _ui.update { it.copy(runNote = "Add your Anthropic API key first (Combos tab), or switch to free reading.") }
            return
        }
        val reader = if (settings.freeReading) null else MatchReader(ComboReader(key, settings.model))
        _ui.update { it.copy(runFor = deck.id, runNote = null, searchFor = null, hits = emptyList()) }
        job = BackgroundWork.launch("replays", "Reading match videos") {
            // the card name index is built here, off the main thread
            val cn = names ?: CardNames(db).also { names = it }
            val allowed = allowedCards(deck, cn)
            val candidates = (allowed.map { cn.name(it) } + ComboViewModel.HAND_TRAPS).distinct()
            var kept = 0
            var dropped = 0
            var failed = 0
            try {
                videos.forEachIndexed { i, hit ->
                    fun progress(stage: String, f: Float) {
                        _ui.update { it.copy(run = RunProgress(i + 1, videos.size, hit.title, stage, f)) }
                        BackgroundWork.update("replays", "${i + 1} of ${videos.size}: $stage\n${hit.title}", (i + f) / videos.size)
                    }
                    val work = VideoWork(File(AppPlatform.current.filesDir, "video-work/replays"), hit.url)
                    try {
                        val r = readOne(deck, hit, work, reader, cn, allowed, candidates, ::progress)
                        work.clear()
                        val lib = library(deck.id)
                        when (r) {
                            is Result.Kept -> { kept++; save(lib.copy(matches = lib.matches.filter { it.url != hit.url } + r.match)) }
                            is Result.Dropped -> { dropped++; save(lib.copy(dropped = lib.dropped.filter { it.url != hit.url } + DroppedVideo(hit.url, hit.title, r.reason))) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ClaudeException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        _ui.update { it.copy(runNote = "Couldn't read \"${hit.title}\": ${e.message ?: e.javaClass.simpleName}") }
                    }
                }
                _ui.update { it.copy(runNote = summary(kept, dropped, failed) + (it.runNote?.let { n -> "\n$n" } ?: "")) }
            } catch (e: CancellationException) {
                _ui.update { it.copy(runNote = "Stopped. " + summary(kept, dropped, failed)) }
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(runNote = (e.message ?: e.toString()) + "\n" + summary(kept, dropped, failed)) }
            } finally {
                _ui.update { it.copy(run = null) }
            }
        }
    }

    private fun summary(kept: Int, dropped: Int, failed: Int) =
        "$kept match${if (kept == 1) "" else "es"} added, $dropped left out" + if (failed > 0) ", $failed couldn't be read." else "."

    private sealed interface Result {
        data class Kept(val match: MatchReplay) : Result
        data class Dropped(val reason: String) : Result
    }

    /**
     * Free reading of a match. With a duel log naming both players (Duelingbook and the like): the
     * side playing the archetype, turns, games and results from the log. Without one: a timeline
     * of the cards shown, in order.
     */
    private suspend fun readFree(
        deck: DeckAnalysis,
        hit: VideoHit,
        video: PreparedVideo,
        cn: CardNames,
        allowed: Set<Long>,
        progress: (String, Float) -> Unit,
    ): Result {
        val handTraps = ComboViewModel.HAND_TRAPS.mapNotNull { cn.resolve(it, emptySet()) }.map { cn.canonical(it) }.toSet()
        val extra = allowed.filter { cn.isExtraDeck(it) }.toSet()
        val r = FreeReader.read(video.frames, cn, allowed, handTraps, hit.title, video.description, extra, video.captions) {
            progress("Reading the games", 0.55f + 0.4f * it)
        }
        val base = MatchReplay(
            url = hit.url, title = hit.title, uploader = hit.uploader, date = video.date.ifEmpty { hit.date },
            format = "", opponent = "", games = emptyList(), sideChanges = emptyList(), lessons = emptyList(),
        )
        val players = r.players
        val score = players.associateWith { p -> r.cards.count { it.player == p && it.code in allowed && it.code !in handTraps } }
        val side = players.maxByOrNull { score[it] ?: 0 }?.takeIf { r.log && players.size == 2 && (score[it] ?: 0) >= 3 }
        if (side == null) {
            // no log with two players: what was shown, in order
            val cards = r.cards.mapNotNull { e -> e.code?.let { TimelineEntry(e.t, it, cn.name(it)) } }
            if (cards.size < 5) return Result.Dropped("Too few card names could be read for free; Claude reading may do better on this video")
            return Result.Kept(base.copy(opponent = "Unknown deck (free reading)", timeline = cards))
        }
        val other = players.first { it != side }
        r.cards.firstOrNull { it.player == side && it.exact && it.code !in allowed && it.code !in handTraps }?.let {
            return Result.Dropped("The ${deck.title} player uses ${cn.name(it.code!!)}, which none of the deck lists play")
        }
        val opponent = r.cards.filter { it.player == other }.mapNotNull { e -> e.code?.let { cn.archetype(it) } }
            .filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "Unknown deck"

        // games end at win lines; turns start at turn lines, or when the other player starts acting
        val games = ArrayList<GameRecord>()
        var turns = ArrayList<TurnRecord>()
        var turnEvents = ArrayList<FreeReader.Event>()
        var turnNo = 0
        fun closeTurn() {
            if (turnEvents.isEmpty()) return
            val actor = turnEvents.firstOrNull { it.code != null }?.player ?: return
            val mine = turnEvents.filter { it.player == actor && it.code != null }
            val summary = mine.groupBy { it.action }.entries.joinToString(" · ") { (a, es) ->
                a.replace('_', ' ').replaceFirstChar { it.uppercase() } + ": " + es.joinToString(", ") { cn.name(it.code!!) }
            }
            turns += TurnRecord(
                turn = ++turnNo, ours = actor == side, summary = summary,
                plays = mine.mapNotNull { it.code }.distinct(), endBoard = emptyList(),
                interrupts = turnEvents.filter { it.player != actor && it.action == "activate" && it.code != null }
                    .map { Interrupt(it.code, cn.name(it.code!!), it.player == side, "") },
            )
            turnEvents = ArrayList()
        }
        fun closeGame(end: FreeReader.Event?) {
            closeTurn()
            if (turns.isEmpty()) return
            val winner = end?.let { e -> listOf(side, other).firstOrNull { it.lowercase() in e.text.lowercase() || it == e.player } }
            val lost = end?.text?.lowercase()?.let { "lost" in it || "surrender" in it || "defeat" in it } == true
            val won = when {
                winner == null -> null
                lost -> winner != side
                else -> winner == side
            }
            games += GameRecord(games.size + 1, turns.first().ours, won, end?.text ?: "", turns, emptyList())
            turns = ArrayList()
            turnNo = 0
        }
        for (e in r.events) {
            when {
                e.action == "game_end" -> closeGame(e)
                e.action == "turn" -> closeTurn()
                e.code != null && turnEvents.isNotEmpty() && e.action != "activate" &&
                    turnEvents.firstOrNull { it.code != null }?.player.let { it != null && it != e.player } -> {
                    closeTurn()
                    turnEvents += e
                }
                else -> turnEvents += e
            }
        }
        closeGame(null)
        if (games.isEmpty()) return Result.Dropped("No game could be read from the duel log")
        return Result.Kept(base.copy(opponent = opponent, games = games, format = "Duel log (free reading)"))
    }

    private suspend fun readOne(
        deck: DeckAnalysis,
        hit: VideoHit,
        work: VideoWork,
        reader: MatchReader?,
        cn: CardNames,
        allowed: Set<Long>,
        candidates: List<String>,
        progress: (String, Float) -> Unit,
    ): Result {
        // whole games: a frame every 2 s, at most 360 kept
        val video = work.prepare(hit.url, "Finding the plays", 0.25f, 0.3f, stepMs = 2000, maxFrames = 360, progress = progress)
        val frames = video.frames
        if (reader == null) return readFree(deck, hit, video, cn, allowed, progress)

        // Which of A / B plays the archetype: the side whose identified cards are mostly in the lists.
        val inLists = mutableMapOf("A" to 0, "B" to 0)
        val outside = mutableMapOf("A" to mutableListOf<Long>(), "B" to mutableListOf<Long>())
        fun ours(): String? {
            val a = inLists.getValue("A"); val b = inLists.getValue("B")
            return when {
                a >= 3 && a >= 2 * b -> "A"
                b >= 3 && b >= 2 * a -> "B"
                // a mirror match: both play the archetype's cards; follow A
                a >= 3 && b >= 3 && outside.getValue("A").isEmpty() -> "A"
                a >= 3 && b >= 3 && outside.getValue("B").isEmpty() -> "B"
                else -> null
            }
        }

        val events = ArrayList<MatchReader.Event>()
        var sides = ""
        val batches = frames.chunked(6)
        batches.forEachIndexed { bi, batch ->
            progress("Reading the games", 0.55f + 0.37f * bi / batches.size)
            val recent = events.map { "%.0fs %s %s %s %s".format(it.t, it.side, it.action, it.card, it.detail).trim() }
            // a batch read in an earlier, interrupted run isn't paid for again
            val got = work.batch(bi)?.let { a ->
                (0 until a.length()).map { k -> a.getJSONObject(k).let { e ->
                    MatchReader.Event(e.optDouble("t"), e.optString("side"), e.optString("action"), e.optString("card"),
                        e.optString("confidence"), e.optString("detail"))
                } }
            } ?: reader.readBatch(batch, candidates, hit.title, video.captions, recent, sides).also { evs ->
                work.saveBatch(bi, org.json.JSONArray(evs.map { e ->
                    org.json.JSONObject().put("t", e.t).put("side", e.side).put("action", e.action).put("card", e.card)
                        .put("confidence", e.confidence).put("detail", e.detail)
                }))
            }
            events += got
            for (e in got) {
                if (e.side !in inLists || e.card.isBlank() || e.confidence == "low") continue
                if (e.action in setOf("hand_shown", "board_shown", "phase", "other", "life_points", "game_end", "side_deck")) continue
                // hand traps both sides may play don't tell the sides apart
                val id = cn.resolve(e.card, allowed) ?: continue
                if (cn.name(id) in ComboViewModel.HAND_TRAPS) continue
                if (cn.canonical(id) in allowed) inLists[e.side] = inLists.getValue(e.side) + 1
                else outside.getValue(e.side).add(id)
            }
            ours()?.let { side ->
                outside.getValue(side).firstOrNull()?.let { id ->
                    return Result.Dropped("The ${deck.title} player uses ${cn.name(id)}, which none of the deck lists play")
                }
            }
            if (sides.isEmpty() && got.isNotEmpty()) sides = "A is the player at the bottom / left, B the other"
        }
        val side = ours() ?: return Result.Dropped("Couldn't tell which player uses ${deck.title}")

        val raw = reader.writeMatch(events, hit.title, video.description, video.captions, deck.title) { part, total ->
            if (part == 0) progress("Outlining the games", 0.92f)
            else progress("Writing game $part of $total", 0.93f + 0.06f * (part - 1) / total)
        }
        val other = if (side == "A") "B" else "A"
        fun resolve(name: String, ourSide: Boolean): Long? = cn.resolve(name, if (ourSide) allowed else emptySet())

        // Final check on what the write-up says our side played.
        for (g in raw.games) for (t in g.turns.filter { it.side == side }) {
            for (n in t.plays + t.endBoard) {
                val id = resolve(n, true) ?: continue
                if (cn.canonical(id) !in allowed && cn.name(id) !in ComboViewModel.HAND_TRAPS)
                    return Result.Dropped("The ${deck.title} player uses ${cn.name(id)}, which none of the deck lists play")
            }
        }
        if (raw.games.isEmpty()) return Result.Dropped("No complete game could be read from this video")

        val opponent = (if (side == "A") raw.sideB else raw.sideA).ifBlank { "Unknown deck" }
        return Result.Kept(
            MatchReplay(
                url = hit.url, title = hit.title, uploader = hit.uploader, date = video.date.ifEmpty { hit.date },
                format = raw.format.takeIf { it != "unknown" } ?: "",
                opponent = opponent,
                games = raw.games.map { g ->
                    GameRecord(
                        number = g.number,
                        weFirst = when (g.first) { side -> true; other -> false; else -> null },
                        won = when (g.winner) { side -> true; other -> false; else -> null },
                        how = g.how,
                        turningPoints = g.turningPoints,
                        turns = g.turns.map { t ->
                            val mine = t.side == side
                            TurnRecord(
                                turn = t.turn, ours = mine, summary = t.summary,
                                plays = t.plays.mapNotNull { resolve(it, mine) },
                                endBoard = t.endBoard.mapNotNull { resolve(it, mine) },
                                interrupts = t.interrupts.map { i ->
                                    val by = i.side == side
                                    Interrupt(resolve(i.card, by), i.card, by, i.on)
                                },
                            )
                        },
                    )
                },
                sideChanges = raw.sideChanges,
                lessons = if (side == "A") raw.lessonsA else raw.lessonsB,
            )
        )
    }

    companion object {
        private val shared = MutableStateFlow(ReplaysUi())
        private var job: Job? = null
        private var names: CardNames? = null
    }
}
