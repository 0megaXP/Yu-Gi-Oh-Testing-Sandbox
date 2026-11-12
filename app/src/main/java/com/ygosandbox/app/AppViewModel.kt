package com.ygosandbox.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ygosandbox.app.data.Banlists
import com.ygosandbox.app.data.CardDatabase
import com.ygosandbox.app.data.DeckSearch
import com.ygosandbox.app.data.DeckStore
import com.ygosandbox.app.data.MyDeckStore
import com.ygosandbox.app.model.DeckPart
import com.ygosandbox.app.model.SavedDeck
import com.ygosandbox.app.data.Settings
import com.ygosandbox.app.model.DeckAnalysis
import com.ygosandbox.app.model.GameFormat
import com.ygosandbox.app.model.TimeSpan
import com.ygosandbox.app.net.ApiException
import com.ygosandbox.app.update.UpdateInfo
import com.ygosandbox.app.update.Updater
import com.ygoengine.AnalysisRequest
import com.ygoengine.Knowledge
import com.ygoengine.SearchSettings
import com.ygoengine.SideState
import com.ygoengine.YgoEngine
import com.ygosandbox.app.play.DeckChoice
import com.ygosandbox.app.play.HAND_SIZE
import com.ygosandbox.app.play.PlaySetup
import com.ygosandbox.app.play.PlayState
import com.ygosandbox.app.play.deckChoices
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Menu : Screen
    data object Decks : Screen
    data object NewSearch : Screen
    data class DeckDetail(val id: String) : Screen
    data object Cards : Screen
    data object MyDecks : Screen
    data class DeckEditor(val id: String) : Screen
    data object Analysis : Screen
    data class AnalysisDeck(val id: String) : Screen
    data object Play : Screen
    data class SidePlan(val deckId: String) : Screen
    data object Settings : Screen
}

enum class DbStatus { LOADING, MISSING, READY, DOWNLOADING, ERROR }

data class UiState(
    val stack: List<Screen> = listOf(Screen.Menu),
    val dbStatus: DbStatus = DbStatus.LOADING,
    val dbProgress: Float = 0f,
    val dbError: String? = null,
    /** Bumped whenever the card list changes, so screens re-read it. */
    val dbRevision: Int = 0,
    val cardCount: Int = 0,
    val archetypes: List<String> = emptyList(),
    val decks: List<DeckAnalysis> = emptyList(),
    /** The user's own decks, most recently edited first. */
    val myDecks: List<SavedDeck> = emptyList(),
    /** Progress text while a deck search runs, otherwise null. */
    val searching: String? = null,
    /** Show deck lists as a grid of card pictures. */
    val deckGrid: Boolean = true,
    val message: String? = null,
    val update: UpdateInfo? = null,
    val updateProgress: Float? = null,
    /** Play engine: setup, progress and results; also the side plan. */
    val play: PlayState = PlayState(),
) {
    val screen: Screen get() = stack.last()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    val db = CardDatabase(app)
    private val store = DeckStore(app)
    private val myStore = MyDeckStore(app)
    private val search = DeckSearch(db)
    private var searchJob: Job? = null

    private val _ui = MutableStateFlow(
        UiState(
            decks = store.load().sortedByDescending { it.createdAt },
            myDecks = myStore.load().sortedByDescending { it.updatedAt },
        ),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        _ui.update { it.copy(deckGrid = settings.deckGrid) }
        checkForUpdates(manual = false)
        viewModelScope.launch { openCardDatabase() }
    }

    // ---------- Navigation ----------

    fun open(screen: Screen) = _ui.update { it.copy(stack = it.stack + screen) }

    /** Returns false when already on the main menu. */
    fun back(): Boolean {
        if (_ui.value.stack.size <= 1) return false
        _ui.update { it.copy(stack = it.stack.dropLast(1)) }
        return true
    }

    private fun replaceTop(screen: Screen) = _ui.update { it.copy(stack = it.stack.dropLast(1) + screen) }

    // ---------- Card database ----------

    private suspend fun openCardDatabase() {
        val loaded = try {
            db.loadLocal()
        } catch (e: Exception) {
            false
        }
        if (loaded) {
            publishDb()
            refreshCardDatabaseIfChanged()
        } else {
            downloadCardDatabase()
        }
    }

    private fun publishDb() = _ui.update {
        it.copy(
            dbStatus = DbStatus.READY, dbError = null, cardCount = db.cards.size,
            archetypes = db.archetypes, dbRevision = it.dbRevision + 1,
        )
    }

    /** Checks at most once a day whether YGOPRODeck has a newer card list. */
    private suspend fun refreshCardDatabaseIfChanged(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - settings.cardDbCheckedAt < 24 * 3600_000L) return
        try {
            val remote = db.remoteVersion()
            settings.cardDbCheckedAt = now
            if (remote != settings.cardDbVersion) downloadCardDatabase(remote)
            else if (force) showMessage("The card database is up to date")
        } catch (e: Exception) {
            if (force) showMessage("Couldn't reach YGOPRODeck: ${e.message}")
        }
    }

    fun updateCardDatabase() {
        if (_ui.value.dbStatus == DbStatus.DOWNLOADING) return
        viewModelScope.launch { refreshCardDatabaseIfChanged(force = true) }
    }

    fun redownloadCardDatabase() {
        if (_ui.value.dbStatus == DbStatus.DOWNLOADING) return
        viewModelScope.launch { downloadCardDatabase() }
    }

    fun retryCardDatabase() {
        viewModelScope.launch { openCardDatabase() }
    }

    private suspend fun downloadCardDatabase(knownVersion: String? = null) {
        val hadCards = db.cards.isNotEmpty()
        _ui.update { it.copy(dbStatus = DbStatus.DOWNLOADING, dbProgress = 0f, dbError = null) }
        try {
            val version = knownVersion ?: runCatching { db.remoteVersion() }.getOrDefault("")
            db.download { p -> _ui.update { it.copy(dbProgress = p) } }
            settings.cardDbVersion = version
            settings.cardDbCheckedAt = System.currentTimeMillis()
            settings.cardDbDownloadedAt = System.currentTimeMillis()
            publishDb()
            if (hadCards) showMessage("Card database updated: ${db.cards.size} cards")
        } catch (e: Exception) {
            if (hadCards) {
                publishDb()
                showMessage("Card database update failed: ${e.message}")
            } else {
                _ui.update { it.copy(dbStatus = DbStatus.ERROR, dbError = e.message ?: e.toString()) }
            }
        }
    }

    // ---------- Decks ----------

    fun banlistDates(format: GameFormat) = Banlists.dates(format, settings.customBanlists(format.name))

    fun startSearch(
        archetypes: List<String>,
        excluded: List<String>,
        format: GameFormat,
        span: TimeSpan,
        replaceId: String? = null,
    ) {
        if (_ui.value.searching != null) return
        if (_ui.value.dbStatus != DbStatus.READY && db.cards.isEmpty()) {
            showMessage("Wait for the card database to finish downloading")
            return
        }
        searchJob = viewModelScope.launch {
            _ui.update { it.copy(searching = "Starting…") }
            try {
                val result = search.run(archetypes, excluded, format, span, banlistDates(format)) { p ->
                    _ui.update { it.copy(searching = p) }
                }
                val list = listOf(result) + _ui.value.decks.filter { it.id != replaceId }
                store.save(list)
                _ui.update { it.copy(decks = list) }
                if (replaceId != null) replaceTop(Screen.DeckDetail(result.id))
                else _ui.update { it.copy(stack = listOf(Screen.Menu, Screen.Decks, Screen.DeckDetail(result.id))) }
            } catch (e: DeckSearch.NoDecksException) {
                showMessage(e.message ?: "No decks found")
            } catch (e: ApiException) {
                showMessage("YGOPRODeck answered with an error (HTTP ${e.code}). Try again later.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Search failed: ${e.message ?: e.toString()}")
            } finally {
                _ui.update { it.copy(searching = null) }
            }
        }
    }

    fun cancelSearch() {
        searchJob?.cancel()
        _ui.update { it.copy(searching = null) }
    }

    fun deleteDeck(id: String) {
        val list = _ui.value.decks.filter { it.id != id }
        store.save(list)
        _ui.update { s -> s.copy(decks = list, stack = s.stack.filter { it != Screen.DeckDetail(id) }) }
    }

    // ---------- My decks ----------

    /** Creates an empty deck and opens it in the editor. */
    fun createDeck(name: String, format: GameFormat = GameFormat.TCG) {
        val now = System.currentTimeMillis()
        val deck = SavedDeck(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "New deck" },
            format = format, main = emptyList(), extra = emptyList(), side = emptyList(),
            createdAt = now, updatedAt = now,
        )
        saveMyDecks(listOf(deck) + _ui.value.myDecks)
        open(Screen.DeckEditor(deck.id))
    }

    /** Copies a tournament deck from a search result into My decks. Returns the new deck's name. */
    fun importDeck(source: com.ygosandbox.app.model.SourceDeck, format: GameFormat): String {
        val now = System.currentTimeMillis()
        fun canon(ids: List<Long>) = ids.map { db.find(it)?.id ?: it }
        val base = source.name.ifBlank { "Imported deck" }
        val taken = _ui.value.myDecks.map { it.name }.toSet()
        val name = if (base !in taken) base else (2..999).map { "$base ($it)" }.first { it !in taken }
        val deck = SavedDeck(
            id = java.util.UUID.randomUUID().toString(), name = name, format = format,
            main = canon(source.main), extra = canon(source.extra), side = canon(source.side),
            createdAt = now, updatedAt = now,
        )
        saveMyDecks(listOf(deck) + _ui.value.myDecks)
        return name
    }

    fun renameDeck(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        changeDeck(id) { it.copy(name = clean) }
    }

    /** Sets a card's roles for the Analysis feature; null goes back to the automatic guess. */
    fun setRoles(deckId: String, cardId: Long, roles: Set<com.ygosandbox.app.model.Role>?) = changeDeck(deckId) { d ->
        d.copy(roles = if (roles == null) d.roles - cardId else d.roles + (cardId to roles))
    }

    fun setDeckFormat(id: String, format: GameFormat) = changeDeck(id) { it.copy(format = format) }

    fun deleteMyDeck(id: String) {
        saveMyDecks(_ui.value.myDecks.filter { it.id != id })
        _ui.update { s -> s.copy(stack = s.stack.filter { it != Screen.DeckEditor(id) }) }
    }

    /**
     * Adds one copy of a card. Extra Deck monsters go to the Extra Deck unless [toSide].
     * Returns a message when the card can't be added.
     */
    fun addCard(deckId: String, cardId: Long, toSide: Boolean): String? {
        val deck = _ui.value.myDecks.firstOrNull { it.id == deckId } ?: return null
        val card = db.find(cardId) ?: return "Card not found"
        val id = card.id
        if (deck.copiesOf(id) >= 3) return "You already have 3 copies of ${card.name}"
        val part = when {
            toSide -> DeckPart.SIDE
            card.isExtraDeck -> DeckPart.EXTRA
            else -> DeckPart.MAIN
        }
        val list = deck.part(part)
        if (list.size >= part.max) return "The ${part.label} is full (${part.max} cards)"
        changeDeck(deckId) { it.withPart(part, list + id) }
        return null
    }

    fun removeCard(deckId: String, cardId: Long, part: DeckPart) = changeDeck(deckId) { d ->
        val list = d.part(part).toMutableList()
        val i = list.lastIndexOf(cardId)
        if (i >= 0) list.removeAt(i)
        d.withPart(part, list)
    }

    /** Moves one copy between the Main/Extra Deck and the Side Deck. */
    fun moveCard(deckId: String, cardId: Long, from: DeckPart): String? {
        val deck = _ui.value.myDecks.firstOrNull { it.id == deckId } ?: return null
        val card = db.find(cardId)
        val to = if (from != DeckPart.SIDE) DeckPart.SIDE else if (card?.isExtraDeck == true) DeckPart.EXTRA else DeckPart.MAIN
        if (deck.part(to).size >= to.max) return "The ${to.label} is full (${to.max} cards)"
        val src = deck.part(from).toMutableList()
        val i = src.lastIndexOf(cardId)
        if (i < 0) return null
        src.removeAt(i)
        changeDeck(deckId) { it.withPart(from, src).withPart(to, it.part(to) + cardId) }
        return null
    }

    private fun changeDeck(id: String, change: (SavedDeck) -> SavedDeck) {
        val list = _ui.value.myDecks
        val old = list.firstOrNull { it.id == id } ?: return
        val updated = change(old).copy(updatedAt = System.currentTimeMillis())
        saveMyDecks(listOf(updated) + list.filter { it.id != id })
    }

    private fun saveMyDecks(list: List<SavedDeck>) {
        _ui.update { it.copy(myDecks = list) }
        try {
            myStore.save(list)
        } catch (e: Exception) {
            showMessage("Couldn't save your decks: ${e.message}")
        }
    }

    // ---------- Banlists ----------

    fun addBanlistDate(format: GameFormat, date: String): Boolean {
        val ok = runCatching { java.time.LocalDate.parse(date.trim()) }.isSuccess
        if (!ok) {
            showMessage("Write the date as YYYY-MM-DD, e.g. 2026-10-19")
            return false
        }
        settings.setCustomBanlists(format.name, settings.customBanlists(format.name) + date.trim())
        _ui.update { it.copy(dbRevision = it.dbRevision + 1) }
        return true
    }

    fun removeBanlistDate(format: GameFormat, date: String) {
        settings.setCustomBanlists(format.name, settings.customBanlists(format.name) - date)
        _ui.update { it.copy(dbRevision = it.dbRevision + 1) }
    }

    // ---------- Updates ----------

    fun checkForUpdates(manual: Boolean) {
        viewModelScope.launch {
            try {
                val info = Updater.check(settings.githubToken)
                _ui.update { it.copy(update = info) }
                if (manual && info == null) showMessage("You have the latest version (${Updater.currentVersion})")
            } catch (e: ApiException) {
                if (manual) showMessage(
                    when (e.code) {
                        401 -> "GitHub rejected the token. Check it in Settings → App version."
                        404 -> if (settings.githubToken.isEmpty()) "Add your GitHub token in Settings → App version first."
                        else "No release found, or the token can't read the Yu-Gi-Oh-Testing-Sandbox repository."
                        else -> "Couldn't check for updates (HTTP ${e.code})"
                    },
                )
            } catch (e: Exception) {
                if (manual) showMessage("Couldn't check for updates: ${e.message}")
            }
        }
    }

    fun installUpdate() {
        val info = _ui.value.update ?: return
        if (_ui.value.updateProgress != null) return
        val app = getApplication<Application>()
        if (!Updater.canInstall(app)) {
            showMessage("Allow YGO Sandbox to install updates, then tap Update again")
            Updater.openInstallPermissionSettings(app)
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(updateProgress = 0f) }
            try {
                val apk = Updater.download(app, info, settings.githubToken) { p -> _ui.update { it.copy(updateProgress = p) } }
                Updater.install(app, apk)
            } catch (e: Exception) {
                showMessage("Update failed: ${e.message}")
            } finally {
                _ui.update { it.copy(updateProgress = null) }
            }
        }
    }

    fun setDeckGrid(grid: Boolean) {
        settings.deckGrid = grid
        _ui.update { it.copy(deckGrid = grid) }
    }

    // ---------- Play engine ----------

    private var engine: YgoEngine? = null
    private val engineLock = Mutex()
    private var playJob: Job? = null
    private var sideJob: Job? = null

    /** Opens the engine once; the first time it copies the card scripts into app storage. */
    private suspend fun engine(): YgoEngine = engineLock.withLock {
        engine ?: run {
            _ui.update { it.copy(play = it.play.copy(preparing = true)) }
            try {
                YgoEngine.open(getApplication<Application>()).also { engine = it }
            } finally {
                _ui.update { it.copy(play = it.play.copy(preparing = false)) }
            }
        }
    }

    fun playDeckChoices(): List<DeckChoice> = deckChoices(_ui.value.myDecks, _ui.value.decks)

    fun deckChoice(key: String?): DeckChoice? = key?.let { k -> playDeckChoices().firstOrNull { it.key == k } }

    fun updatePlaySetup(change: (PlaySetup) -> PlaySetup) = _ui.update { s ->
        val before = s.play.setup
        var after = change(before)
        // A different deck invalidates the hand picked from the old one.
        if (after.myDeck != before.myDeck) after = after.copy(myHand = emptyList())
        if (after.opponentDeck != before.opponentDeck) after = after.copy(opponentHand = emptyList())
        s.copy(play = s.play.copy(setup = after, error = null))
    }

    /** Deals a random opening hand from the chosen deck. */
    fun dealHand(mine: Boolean) {
        val setup = _ui.value.play.setup
        val deck = deckChoice(if (mine) setup.myDeck else setup.opponentDeck) ?: return
        val hand = deck.main.shuffled().take(HAND_SIZE)
        updatePlaySetup { if (mine) it.copy(myHand = hand) else it.copy(opponentHand = hand) }
    }

    fun runPlay() {
        if (playJob?.isActive == true) return
        val setup = _ui.value.play.setup
        val mine = deckChoice(setup.myDeck)
        val theirs = deckChoice(setup.opponentDeck)
        val problem = when {
            mine == null -> "Pick your deck"
            theirs == null -> "Pick the opponent's deck"
            setup.myHand.size != HAND_SIZE -> "Pick your $HAND_SIZE-card hand (or tap Random)"
            else -> null
        }
        if (problem != null) {
            _ui.update { it.copy(play = it.play.copy(error = problem)) }
            return
        }
        val me = SideState(deck = mine!!.toEngine(), hand = setup.myHand)
        val opp = SideState(
            deck = theirs!!.toEngine(),
            hand = if (!setup.real && setup.opponentHand.size == HAND_SIZE) setup.opponentHand else null,
        )
        val request = AnalysisRequest(
            first = if (setup.goingFirst) me else opp,
            second = if (setup.goingFirst) opp else me,
            me = if (setup.goingFirst) 0 else 1,
            knowledge = if (setup.real) Knowledge.REAL else Knowledge.PRACTICE,
            seed = System.currentTimeMillis(),
            settings = SearchSettings(
                iterations = setup.depth.iterations,
                verifyIterations = setup.depth.verifyIterations,
                maxSeconds = 240.0,
                // Leave a core free so the phone stays responsive.
                threads = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1),
            ),
        )
        playJob = viewModelScope.launch {
            _ui.update { it.copy(play = it.play.copy(error = null, progress = com.ygoengine.Progress("starting", 0, 0))) }
            try {
                val result = engine().analyze(request) { p -> _ui.update { it.copy(play = it.play.copy(progress = p)) } }
                _ui.update { it.copy(play = it.play.copy(result = result, resultSetup = setup)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(play = it.play.copy(error = "The engine failed: ${e.message ?: e.toString()}")) }
            } finally {
                _ui.update { it.copy(play = it.play.copy(progress = null)) }
            }
        }
    }

    /** Stops the search early; the lines found so far are still shown. */
    fun stopPlay() {
        engine?.cancel()
    }

    fun setSideOpponent(deckId: String, opponent: String?) = _ui.update {
        it.copy(play = it.play.copy(sideDeck = deckId, sideOpponent = opponent, sideError = null))
    }

    fun setSideSimulate(on: Boolean) = _ui.update { it.copy(play = it.play.copy(sideSimulate = on)) }

    fun runSidePlan(deckId: String) {
        if (sideJob?.isActive == true) return
        val play = _ui.value.play
        val mine = deckChoice("my:$deckId")
        val theirs = deckChoice(play.sideOpponent)
        if (mine == null || theirs == null) {
            _ui.update { it.copy(play = it.play.copy(sideError = "Pick the opponent's deck")) }
            return
        }
        sideJob = viewModelScope.launch {
            _ui.update {
                it.copy(play = it.play.copy(sideError = null, sideResult = null, sideProgress = com.ygoengine.Progress("starting", 0, 0)))
            }
            try {
                val result = engine().side(
                    mine.toEngine(), theirs.toEngine(),
                    simulateTurns = if (play.sideSimulate) 6 else 0,
                    iterationsPerTurn = 500,
                ) { p -> _ui.update { it.copy(play = it.play.copy(sideProgress = p)) } }
                _ui.update { it.copy(play = it.play.copy(sideResult = result, sideDeck = deckId)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(play = it.play.copy(sideError = "The engine failed: ${e.message ?: e.toString()}")) }
            } finally {
                _ui.update { it.copy(play = it.play.copy(sideProgress = null)) }
            }
        }
    }

    fun stopSidePlan() {
        engine?.cancel()
    }

    override fun onCleared() {
        engine?.close()
        engine = null
    }

    fun showMessage(text: String) = _ui.update { it.copy(message = text) }
    fun dismissMessage() = _ui.update { it.copy(message = null) }
}
