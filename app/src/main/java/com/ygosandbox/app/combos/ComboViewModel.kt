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
import java.io.File

data class RunProgress(val video: Int, val videos: Int, val title: String, val stage: String, val fraction: Float)

data class CombosUi(
    /** deck id -> what has been read for it */
    val libraries: Map<String, ComboLibrary> = emptyMap(),
    /** deck id the search below belongs to */
    val searchFor: String? = null,
    val searching: Boolean = false,
    val hits: List<VideoHit> = emptyList(),
    val searchError: String? = null,
    /** deck id being read, and how far */
    val runFor: String? = null,
    val run: RunProgress? = null,
    val runNote: String? = null,
)

/**
 * Finds combo videos for an archetype search result and reads them on the phone.
 * A video is dropped as soon as its combo uses a card that none of the result's tournament
 * lists play, so the lines kept are ones these lists can actually make.
 */
class ComboViewModel(app: Application) : AndroidViewModel(app) {
    val settings = ComboSettings(app)
    private val store = ComboStore(app)
    private val _ui = MutableStateFlow(CombosUi())
    val ui: StateFlow<CombosUi> = _ui
    private var job: Job? = null
    private var names: CardNames? = null

    fun library(deckId: String): ComboLibrary {
        _ui.value.libraries[deckId]?.let { return it }
        val lib = store.load(deckId)
        _ui.update { it.copy(libraries = it.libraries + (deckId to lib)) }
        return lib
    }

    fun search(deck: DeckAnalysis, since: String?) {
        if (job?.isActive == true) return
        _ui.update { it.copy(searchFor = deck.id, searching = true, hits = emptyList(), searchError = null) }
        job = viewModelScope.launch {
            try {
                val hits = VideoSource.search(deck.archetypes.joinToString(" "), since)
                _ui.update { it.copy(hits = hits, searchError = if (hits.isEmpty()) "No combo videos found for this archetype in that period." else null) }
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
        save(lib.copy(videos = lib.videos.filter { it.url != url }, dropped = lib.dropped.filter { it.url != url }))
    }

    fun clear(deckId: String) {
        store.delete(deckId)
        _ui.update { it.copy(libraries = it.libraries - deckId) }
    }

    private fun save(lib: ComboLibrary) {
        val l = lib.copy(updatedAt = System.currentTimeMillis())
        store.save(l)
        _ui.update { it.copy(libraries = it.libraries + (l.deckId to l)) }
    }

    /** Reads [videos] one after another; one failing video doesn't stop the rest. */
    fun read(deck: DeckAnalysis, db: CardDatabase, videos: List<VideoHit>) {
        if (job?.isActive == true || videos.isEmpty()) return
        val key = settings.apiKey
        if (key.isBlank()) {
            _ui.update { it.copy(runNote = "Add your Anthropic API key first.") }
            return
        }
        val reader = ComboReader(key, settings.model)
        val cn = names ?: CardNames(db).also { names = it }
        val allowed = allowedCards(deck, cn)
        val candidates = (allowed.map { cn.name(it) } + HAND_TRAPS).distinct()
        _ui.update { it.copy(runFor = deck.id, runNote = null, searchFor = null, hits = emptyList()) }
        job = viewModelScope.launch {
            var kept = 0
            var dropped = 0
            var failed = 0
            try {
                videos.forEachIndexed { i, hit ->
                    fun progress(stage: String, f: Float) =
                        _ui.update { it.copy(run = RunProgress(i + 1, videos.size, hit.title, stage, f)) }
                    val work = File(getApplication<Application>().cacheDir, "combo-video")
                    try {
                        when (val r = readOne(hit, work, reader, cn, allowed, candidates, ::progress)) {
                            is Outcome.Kept -> { kept++; save(library(deck.id).let { l -> l.copy(videos = l.videos.filter { it.url != hit.url } + r.combo) }) }
                            is Outcome.Dropped -> { dropped++; save(library(deck.id).let { l -> l.copy(dropped = l.dropped.filter { it.url != hit.url } + DroppedVideo(hit.url, hit.title, r.reason)) }) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ClaudeException) {
                        throw e // a bad key or model fails every video the same way
                    } catch (e: Exception) {
                        failed++
                        _ui.update { it.copy(runNote = "Couldn't read \"${hit.title}\": ${e.message ?: e.javaClass.simpleName}") }
                    } finally {
                        work.deleteRecursively()
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
        "$kept video${if (kept == 1) "" else "s"} added, $dropped left out for cards not in the lists" +
            if (failed > 0) ", $failed couldn't be read." else "."

    private sealed interface Outcome {
        data class Kept(val combo: VideoCombo) : Outcome
        data class Dropped(val reason: String) : Outcome
    }

    private suspend fun readOne(
        hit: VideoHit,
        work: File,
        reader: ComboReader,
        cn: CardNames,
        allowed: Set<Long>,
        candidates: List<String>,
        progress: (String, Float) -> Unit,
    ): Outcome {
        work.deleteRecursively()
        progress("Downloading", 0f)
        val video = VideoSource.fetch(hit.url, work) { progress("Downloading", it * 0.3f) }
        progress("Finding the steps", 0.3f)
        val frames = Frames.extract(video.file, File(work, "frames")) { progress("Finding the steps", 0.3f + it * 0.15f) }
        video.file.delete()
        if (frames.isEmpty()) throw IllegalStateException("No frames could be read from the video")

        fun notInLists(id: Long) = cn.canonical(id) !in allowed

        // Pass 1, stopping at the first card the lists don't play: no need to pay for the rest.
        val events = ArrayList<ComboReader.Event>()
        val batches = frames.chunked(6)
        batches.forEachIndexed { bi, batch ->
            progress("Reading the video", 0.45f + 0.45f * bi / batches.size)
            val recent = events.map { "%.0fs %s %s %s".format(it.t, it.action, it.card, it.detail).trim() }
            val got = reader.readBatch(batch, candidates, hit.title, video.captions, recent)
            events += got
            got.firstOrNull { e ->
                e.player == "combo" && e.confidence != "low" && e.card.isNotBlank() &&
                    e.action !in setOf("hand_shown", "board_shown", "phase", "other") &&
                    cn.resolve(e.card, allowed)?.let(::notInLists) == true
            }?.let { e ->
                val id = cn.resolve(e.card, allowed)!!
                return Outcome.Dropped("Uses ${cn.name(id)} (at ${clock(e.t)}), which none of the deck lists play")
            }
        }

        progress("Writing the combo", 0.9f)
        val raw = reader.writeCombo(events, candidates, hit.title, video.description, video.captions)

        // Final check on the written combo: every card the line plays or ends on must be in the lists.
        // Cards only held in hand (a hand trap kept for the opponent's turn) aren't part of the combo.
        val used = raw.steps.map { it.card }.filter { it.isNotBlank() } +
            raw.endBoard.filter { (_, where) -> BoardCard(0, "", where).onField }.map { it.first }
        for (name in used.distinct()) {
            val id = cn.resolve(name, allowed) ?: return Outcome.Dropped("Couldn't identify \"$name\", so the line can't be checked against the lists")
            if (notInLists(id)) return Outcome.Dropped("Uses ${cn.name(id)}, which none of the deck lists play")
        }
        if (raw.steps.isEmpty() || raw.endBoard.none { (_, where) -> BoardCard(0, "", where).onField }) {
            return Outcome.Dropped("No complete combo could be read from this video")
        }
        val handIds = raw.hand.mapNotNull { cn.resolve(it, allowed) }
        return Outcome.Kept(
            VideoCombo(
                url = hit.url, title = hit.title, uploader = hit.uploader,
                date = video.date.ifEmpty { hit.date }, going = raw.going,
                hand = handIds,
                steps = raw.steps.map { s ->
                    val id = s.card.takeIf { it.isNotBlank() }?.let { cn.resolve(it, allowed) }
                    ComboStep(s.t, s.action, id, id?.let(cn::name) ?: s.card, s.detail)
                },
                endBoard = raw.endBoard.mapNotNull { (n, where) -> cn.resolve(n, allowed)?.let { BoardCard(it, cn.name(it), where) } },
                interruptions = raw.interruptions,
                chokePoints = raw.chokePoints,
                uncertain = raw.uncertain,
            )
        )
    }

    companion object {
        fun clock(t: Double): String = "%d:%02d".format(t.toInt() / 60, t.toInt() % 60)

        /** Shown in most combo videos as "what if they have X"; not part of any line. */
        val HAND_TRAPS = listOf(
            "Ash Blossom & Joyous Spring", "Maxx \"C\"", "Effect Veiler", "Infinite Impermanence",
            "Ghost Ogre & Snow Rabbit", "Ghost Belle & Haunted Mansion", "Droll & Lock Bird", "Nibiru, the Primal Being",
            "Called by the Grave", "Crossout Designator", "Mulcharmy Fuwalos", "Mulcharmy Purulia", "Mulcharmy Meowls",
            "Dimension Shifter", "Ghost Mourner & Moonlit Chill", "Triple Tactics Talent", "Forbidden Droplet",
        )
    }
}
