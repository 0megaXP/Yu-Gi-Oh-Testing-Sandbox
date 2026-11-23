package com.ygosandbox.app.combos

import androidx.lifecycle.ViewModel
import com.ygosandbox.app.data.CardDatabase
import com.ygosandbox.app.model.DeckAnalysis
import com.ygosandbox.app.work.BackgroundWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

data class GuidesUi(
    val libraries: Map<String, GuideLibrary> = emptyMap(),
    /** The deck being worked on, while a search or reading runs. */
    val runFor: String? = null,
    val stage: String = "",
    val fraction: Float = 0f,
    val note: String? = null,
)

/**
 * The Analysis tab: finds the best YouTube guides for an archetype, reads their captions with
 * Claude, and keeps what they teach. The combos they explain go to the Combos tab's library
 * (checked by the engine like any video line); the rest feeds the bots ([GuideSummary]).
 */
class GuideViewModel : ViewModel() {
    val settings = ComboSettings()
    private val store = GuideStore()
    private val _ui = shared
    val ui: StateFlow<GuidesUi> = _ui

    fun library(deckId: String): GuideLibrary {
        _ui.value.libraries[deckId]?.let { return it }
        val lib = store.load(deckId)
        _ui.update { it.copy(libraries = it.libraries + (deckId to lib)) }
        return lib
    }

    fun stop() { job?.cancel() }

    fun clear(deckId: String) {
        store.delete(deckId)
        _ui.update { it.copy(libraries = it.libraries - deckId) }
    }

    private fun save(lib: GuideLibrary) {
        val l = lib.copy(updatedAt = System.currentTimeMillis())
        store.save(l)
        _ui.update { it.copy(libraries = it.libraries + (l.deckId to l)) }
    }

    /**
     * Searches YouTube for guides to [deck]'s archetype and reads the [count] best ones not read
     * yet. [onCombos] gets the explained combos that can be checked (all cards in the lists).
     */
    fun findAndRead(deck: DeckAnalysis, db: CardDatabase, count: Int = 10, onCombos: (List<VideoCombo>) -> Unit) {
        if (job?.isActive == true) return
        val key = settings.apiKey
        if (key.isBlank()) {
            _ui.update { it.copy(runFor = deck.id, note = "Add your Anthropic API key in the Combos tab first: guides are read with Claude.") }
            return
        }
        val reader = GuideReader(key, settings.model)
        val archetype = deck.archetypes.joinToString(" ")
        _ui.update { it.copy(runFor = deck.id, note = null, stage = "Searching YouTube", fraction = 0f) }
        job = BackgroundWork.launch("guides", "Reading deck guides") {
            var read = 0
            var noCaptions = 0
            var failed = 0
            val lines = ArrayList<VideoCombo>()
            try {
                // guides for the deck's own period first; older ones only when too few are newer
                val since = deck.since.ifBlank { VideoSource.daysAgo(365) }
                var hits = VideoSource.search(archetype, since, limit = 30, kind = VideoSource.Kind.GUIDE)
                if (hits.size < count) hits = (hits + VideoSource.search(archetype, null, limit = 30, kind = VideoSource.Kind.GUIDE)).distinctBy { it.url }
                val done = library(deck.id).videos.filter { it.status != "failed" }.map { it.url }.toSet()
                val todo = hits.filter { it.url !in done }.take(count)
                if (todo.isEmpty()) {
                    _ui.update { it.copy(note = if (hits.isEmpty()) "No guide videos found for this archetype." else "Every guide found has been read already.") }
                    return@launch
                }
                val cn = names ?: CardNames(db).also { names = it }
                val allowed = allowedCards(deck, cn)
                val pool = (allowed.map { cn.name(it) } + ComboViewModel.HAND_TRAPS).distinct()
                todo.forEachIndexed { i, hit ->
                    fun stage(s: String, f: Float) {
                        _ui.update { it.copy(stage = "${i + 1} of ${todo.size}: $s\n${hit.title}", fraction = (i + f) / todo.size) }
                        BackgroundWork.update("guides", "${i + 1} of ${todo.size}: $s\n${hit.title}", (i + f) / todo.size)
                    }
                    val video = try {
                        stage("Getting the captions", 0.1f)
                        val text = VideoSource.fetchText(hit.url)
                        if (text.captions.isBlank()) {
                            noCaptions++
                            GuideVideo(hit.url, hit.title, hit.uploader, hit.date, hit.seconds, "no_captions",
                                "This video has no English captions")
                        } else {
                            stage("Reading with Claude", 0.3f)
                            val raw = reader.read(archetype, hit.title, hit.uploader, text.description, text.captions, pool)
                            if (!raw.optBoolean("relevant", true)) {
                                GuideVideo(hit.url, hit.title, hit.uploader, text.date.ifEmpty { hit.date }, hit.seconds, "failed",
                                    "Not a guide for this archetype", readAt = System.currentTimeMillis())
                            } else {
                                val g = parse(raw, cn, allowed)
                                read++
                                g.combos.forEachIndexed { k, c -> toVideoCombo(hit, text.date, k, c, allowed, cn)?.let(lines::add) }
                                GuideVideo(hit.url, hit.title, hit.uploader, text.date.ifEmpty { hit.date }, hit.seconds, "read",
                                    reading = g, readAt = System.currentTimeMillis())
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ClaudeException) {
                        throw e // a bad key or model fails every video the same way
                    } catch (e: Exception) {
                        failed++
                        GuideVideo(hit.url, hit.title, hit.uploader, hit.date, hit.seconds, "failed", e.message ?: e.javaClass.simpleName)
                    }
                    save(library(deck.id).let { l ->
                        l.copy(archetypes = deck.archetypes, videos = l.videos.filter { it.url != hit.url } + video)
                    })
                }
                _ui.update { it.copy(note = summary(read, noCaptions, failed, lines.size)) }
            } catch (e: CancellationException) {
                _ui.update { it.copy(note = "Stopped. " + summary(read, noCaptions, failed, lines.size)) }
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(note = (e.message ?: e.toString()) + "\n" + summary(read, noCaptions, failed, lines.size)) }
            } finally {
                if (lines.isNotEmpty()) onCombos(lines)
                _ui.update { it.copy(stage = "", fraction = 0f) }
            }
        }
    }

    private fun summary(read: Int, noCaptions: Int, failed: Int, combos: Int) =
        "$read guide${if (read == 1) "" else "s"} read" +
            (if (combos > 0) ", $combos combo${if (combos == 1) "" else "s"} sent to the engine to check" else "") +
            (if (noCaptions > 0) ", $noCaptions without captions" else "") +
            (if (failed > 0) ", $failed couldn't be read" else "") + "."

    companion object {
        private val shared = MutableStateFlow(GuidesUi())
        private var job: Job? = null
        private var names: CardNames? = null

        fun isRunning() = job?.isActive == true

        /** Claude's answer with card names resolved: the archetype's cards first, then any card. */
        internal fun parse(o: JSONObject, cn: CardNames, allowed: Set<Long>): DeckGuideReading {
            fun card(name: String, note: String = ""): GuideCard {
                val id = name.takeIf { it.isNotBlank() }?.let { cn.resolve(it, allowed) ?: cn.exact(it) } ?: 0L
                return GuideCard(id, if (id != 0L) cn.name(id) else name, note)
            }
            fun cards(key: String): List<GuideCard> = o.optJSONArray(key).objects().map { card(it.optString("card"), it.optString("note")) }
                .filter { it.name.isNotBlank() }.distinctBy { if (it.code != 0L) it.code.toString() else it.name.lowercase() }
            val combos = o.optJSONArray("combos").objects().map { c ->
                GuideCombo(
                    name = c.optString("name"),
                    going = c.optString("going", "unknown"),
                    hand = c.optJSONArray("starting_hand").strings().map { card(it) },
                    steps = c.optJSONArray("steps").objects().map { s ->
                        val g = card(s.optString("card"))
                        ComboStep(null, s.optString("action"), g.code.takeIf { it != 0L }, g.name, s.optString("detail"))
                    },
                    endBoard = c.optJSONArray("end_board").objects().map { b ->
                        val g = card(b.optString("card"))
                        BoardCard(g.code, g.name, b.optString("where"))
                    },
                )
            }
            return DeckGuideReading(
                summary = o.optString("summary"),
                strategy = o.optString("strategy"),
                starters = cards("starters"),
                extenders = cards("extenders"),
                searchFirst = cards("search_first"),
                protect = cards("protect"),
                chokePoints = cards("choke_points"),
                weakTo = cards("weak_to"),
                buildNotes = cards("build_notes"),
                combos = combos,
                tips = o.optJSONArray("tips").strings().filter { it.isNotBlank() },
            )
        }

        /**
         * An explained combo as a Combos tab line, when the engine can check it: a starting hand,
         * at least three steps, and every card it plays in the archetype's lists.
         */
        internal fun toVideoCombo(hit: VideoHit, date: String, index: Int, c: GuideCombo, allowed: Set<Long>, cn: CardNames): VideoCombo? {
            val hand = c.hand.map { it.code }
            if (hand.isEmpty() || 0L in hand || c.steps.size < 3) return null
            val used = c.steps.mapNotNull { it.code } + c.endBoard.filter { it.onField }.map { it.code }
            if (c.steps.any { it.code == null && it.name.isNotBlank() } || used.any { it == 0L || cn.canonical(it) !in allowed }) return null
            return VideoCombo(
                url = "${hit.url}#guide-$index",
                title = "${hit.title} — ${c.name.ifBlank { "combo ${index + 1}" }} (guide)",
                uploader = hit.uploader, date = date.ifEmpty { hit.date }, going = c.going,
                hand = hand, steps = c.steps, endBoard = c.endBoard.filter { it.code != 0L },
                interruptions = emptyList(), chokePoints = emptyList(), uncertain = listOf("Read from the captions only"),
            )
        }

        private fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
        private fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()

        /**
         * Guide advice for a duel deck with [cards]: from the analysed archetype whose guides name
         * the most of its cards (at least three), or null.
         */
        fun summaryFor(cards: Set<Long>, canonical: (Long) -> Long): GuideSummary? {
            val deck = cards.map(canonical).toSet()
            return GuideStore().all().mapNotNull { GuideSummary.of(it) }.map { s ->
                val named = (s.starters + s.extenders + s.searchFirst + s.protect + s.chokePoints)
                    .map { it.card.code }.filter { it != 0L }.map(canonical).toSet()
                s to named.count { it in deck }
            }.filter { it.second >= 3 }.maxByOrNull { it.second }?.first
        }
    }
}
