package com.ygosandbox.app.combos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
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
class ReplayViewModel(app: Application) : AndroidViewModel(app) {
    val settings = ComboSettings(app)
    private val store = ReplayStore(app)
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
        if (key.isBlank()) {
            _ui.update { it.copy(runNote = "Add your Anthropic API key first (Combos tab).") }
            return
        }
        val reader = MatchReader(ComboReader(key, settings.model))
        _ui.update { it.copy(runFor = deck.id, runNote = null, searchFor = null, hits = emptyList()) }
        job = BackgroundWork.launch(getApplication<Application>(), "replays", "Reading match videos") {
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
                    val work = VideoWork(File(getApplication<Application>().filesDir, "video-work/replays"), hit.url)
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

    private suspend fun readOne(
        deck: DeckAnalysis,
        hit: VideoHit,
        work: VideoWork,
        reader: MatchReader,
        cn: CardNames,
        allowed: Set<Long>,
        candidates: List<String>,
        progress: (String, Float) -> Unit,
    ): Result {
        // whole games: a frame every 2 s, at most 360 kept
        val video = work.prepare(hit.url, "Finding the plays", 0.25f, 0.3f, stepMs = 2000, maxFrames = 360, progress = progress)
        val frames = video.frames

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
