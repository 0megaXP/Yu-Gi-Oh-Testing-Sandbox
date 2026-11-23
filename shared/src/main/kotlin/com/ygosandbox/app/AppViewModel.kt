package com.ygosandbox.app

import androidx.lifecycle.ViewModel
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
import com.ygosandbox.app.play.DuelMode
import com.ygosandbox.app.play.DuelSetup
import com.ygosandbox.app.play.DuelUi
import com.ygosandbox.app.play.LogEntry
import com.ygosandbox.app.play.FirstPlayer
import com.ygoengine.LiveDuel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
import com.ygosandbox.app.platform.CrashReports
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
    data object Duel : Screen
    /** Reference end boards for a deck choice key ("my:<id>" or "src:<search>:<index>"). */
    data class Reference(val deckKey: String) : Screen
    data object ComboTree : Screen
    /** Your YGO Omega matches (Best of 3), from the replay folder. */
    data object MatchHistory : Screen
    /** One game of the match history, replayed move by move. */
    data class ReplayViewer(val gameId: String) : Screen
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
    /** Duel simulator. */
    val duel: DuelUi = DuelUi(),
) {
    val screen: Screen get() = stack.last()
}

class AppViewModel : ViewModel() {
    val settings = Settings()
    val db = CardDatabase()
    private val store = DeckStore()
    private val myStore = MyDeckStore()
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

    fun open(screen: Screen) {
        CrashReports.note("Open $screen")
        _ui.update { it.copy(stack = it.stack + screen) }
    }

    /** What the app is doing now, for crash reports. */
    fun crashContext(): String {
        val u = _ui.value
        val d = u.duel
        val duel = d.state?.let { s ->
            " · duel: ${d.names.joinToString(" vs ")}, turn ${s.turn}, ${s.phase}, LP ${s.players.joinToString("/") { it.lp.toString() }}" +
                (s.decision?.let { " · waiting for player ${it.player + 1} (${it.type})" } ?: "")
        } ?: if (d.active) " · duel starting" else ""
        return "Screen: ${u.screen}$duel"
    }

    /** Returns false when already on the main menu. */
    fun back(): Boolean {
        CrashReports.note("Back from ${_ui.value.screen}")
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
        keepAwake("Checking the card database") { refreshCardDatabaseIfChanged(force = true) }
    }

    fun redownloadCardDatabase() {
        if (_ui.value.dbStatus == DbStatus.DOWNLOADING) return
        keepAwake("Downloading the card database") { downloadCardDatabase() }
    }

    fun retryCardDatabase() {
        keepAwake("Downloading the card database") { openCardDatabase() }
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
        searchJob = keepAwake("Searching tournament decks") {
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

    /** Set by MainActivity (onScreen): whether the app is on screen. */
    private var visible = true
    private var pendingInstall: java.io.File? = null

    fun onScreen(visible: Boolean) {
        this.visible = visible
        if (visible) pendingInstall?.let { apk ->
            pendingInstall = null
            Updater.install(apk)
        }
    }

    fun installUpdate() {
        val info = _ui.value.update ?: return
        if (_ui.value.updateProgress != null) return
        if (!Updater.canInstall()) {
            showMessage("Allow YGO Sandbox to install updates, then tap Update again")
            Updater.openInstallPermissionSettings()
            return
        }
        keepAwake("Downloading the update") {
            _ui.update { it.copy(updateProgress = 0f) }
            try {
                val apk = Updater.download(info, settings.githubToken) { p ->
                    _ui.update { it.copy(updateProgress = p) }
                    com.ygosandbox.app.work.BackgroundWork.update("app:Downloading the update", "${(p * 100).toInt()}%", p)
                }
                // Android only opens the installer while the app is on screen: with the phone
                // locked, it waits for the app to come back (see onScreen).
                if (visible) Updater.install(apk) else pendingInstall = apk
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

    /**
     * Like viewModelScope.launch, but held by the background-work service while it runs, so a
     * long search or analysis carries on when the app is in the background (with a notification
     * and a Stop button).
     */
    private fun keepAwake(title: String, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): Job {
        val key = "app:$title"
        val job = viewModelScope.launch(block = block)
        com.ygosandbox.app.work.BackgroundWork.start(key, title) { job.cancel() }
        job.invokeOnCompletion { com.ygosandbox.app.work.BackgroundWork.finish(key) }
        return job
    }

    /** Opens the engine once; the first time it copies the card scripts into app storage. */
    suspend fun engine(): YgoEngine = engineLock.withLock {
        engine ?: run {
            _ui.update { it.copy(play = it.play.copy(preparing = true)) }
            try {
                com.ygosandbox.app.platform.AppPlatform.current.openEngine().also { engine = it }
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
        val me = SideState(deck = mine!!.toEngine(::cardName), hand = setup.myHand)
        val opp = SideState(
            deck = theirs!!.toEngine(::cardName),
            hand = if (!setup.real && setup.opponentHand.size == HAND_SIZE) setup.opponentHand else null,
        )
        val request = AnalysisRequest(
            first = if (setup.goingFirst) me else opp,
            second = if (setup.goingFirst) opp else me,
            me = if (setup.goingFirst) 0 else 1,
            knowledge = if (setup.real) Knowledge.REAL else Knowledge.PRACTICE,
            seed = System.currentTimeMillis(),
            // Your deck's combo pieces, if its reference boards were built.
            library = pieceLibrary(mine),
            policy = policies.load(),
            imitate = imitations(mine),
            settings = SearchSettings(
                iterations = setup.depth.iterations,
                verifyIterations = setup.depth.verifyIterations,
                maxSeconds = 240.0,
                // Leave a core free so the phone stays responsive.
                threads = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1),
            ),
        )
        playJob = keepAwake("Play engine is searching") {
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

    // ---------- Combo tree ----------

    private var treeJob: Job? = null

    /** Main line for your hand going first, and how to continue after each likely hand trap. */
    fun runComboTree() {
        if (treeJob?.isActive == true) {
            open(Screen.ComboTree)
            return
        }
        val setup = _ui.value.play.setup
        val mine = deckChoice(setup.myDeck)
        val problem = when {
            mine == null -> "Pick your deck"
            setup.myHand.size != HAND_SIZE -> "Pick your $HAND_SIZE-card hand (or tap Random)"
            else -> null
        }
        if (problem != null) {
            _ui.update { it.copy(play = it.play.copy(error = problem)) }
            return
        }
        val opponent = deckChoice(setup.opponentDeck)
        open(Screen.ComboTree)
        treeJob = keepAwake("Building the combo tree") {
            _ui.update {
                it.copy(play = it.play.copy(treeError = null, tree = null, treeSetup = setup,
                    treeProgress = com.ygoengine.Progress("starting", 0, 0)))
            }
            try {
                val tree = engine().comboTree(
                    mine!!.toEngine(::cardName), setup.myHand, opponent?.toEngine(::cardName),
                    iterations = setup.depth.iterations,
                    branchIterations = (setup.depth.iterations / 6).coerceIn(150, 800),
                    seed = System.currentTimeMillis(),
                    library = pieceLibrary(mine),
                    policy = policies.load(),
                    imitate = imitations(mine),
                ) { p -> _ui.update { it.copy(play = it.play.copy(treeProgress = p)) } }
                _ui.update { it.copy(play = it.play.copy(tree = tree)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(play = it.play.copy(treeError = "The engine failed: ${e.message ?: e.toString()}")) }
            } finally {
                _ui.update { it.copy(play = it.play.copy(treeProgress = null)) }
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
        sideJob = keepAwake("Building the side plan") {
            _ui.update {
                it.copy(play = it.play.copy(sideError = null, sideResult = null, sideProgress = com.ygoengine.Progress("starting", 0, 0)))
            }
            try {
                val result = engine().side(
                    mine.toEngine(::cardName), theirs.toEngine(::cardName),
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

    // ---------- Reference end boards ----------

    val references = com.ygosandbox.app.data.ReferenceStore()
    private var refJob: Job? = null

    private val videoCombos = com.ygosandbox.app.combos.ComboStore()

    /** The engine's learned move policy, shared by all decks (see PolicyStore). */
    val policies = com.ygosandbox.app.data.PolicyStore()

    /**
     * The combo pieces the engine's search starts from for a deck: its reference boards' pieces
     * plus those of verified video lines the deck can play (Combos tab).
     */
    fun pieceLibrary(choice: DeckChoice?): String? {
        choice ?: return null
        val video = runCatching {
            com.ygosandbox.app.combos.VideoPieces.libraryFor(videoCombos, (choice.main + choice.extra).toSet()) { id -> db.find(id)?.id ?: id }
        }.getOrNull()
        // and pieces of verified YGO Omega replay lines (any deck's) this deck can play
        val replays = runCatching {
            com.ygosandbox.app.combos.ReplayKnowledge.libraryFor(
                com.ygosandbox.app.combos.ReplayLearning.database(), (choice.main + choice.extra).toSet()) { id -> db.find(id)?.id ?: id }
        }.getOrNull()
        return com.ygosandbox.app.combos.VideoPieces.merge(com.ygosandbox.app.combos.VideoPieces.merge(referenceFor(choice)?.libraryJson, video), replays)
    }

    @Volatile private var cardNames: Pair<Int, com.ygosandbox.app.combos.CardNames>? = null
    private fun names(): com.ygosandbox.app.combos.CardNames =
        cardNames?.takeIf { it.first == db.cards.size }?.second
            ?: com.ygosandbox.app.combos.CardNames(db).also { cardNames = db.cards.size to it }

    /** Choices players make in the analysed replays that the engine doesn't understand yet, for this deck. */
    fun imitations(choice: DeckChoice?): List<com.ygoengine.Imitation> = runCatching {
        choice ?: return emptyList()
        com.ygosandbox.app.combos.ReplayKnowledge.imitationsFor(
            com.ygosandbox.app.combos.ReplayLearning.database(), (choice.main + choice.extra).toSet()) { id -> db.find(id)?.id ?: id }
    }.getOrDefault(emptyList())

    /** What the replay analysis knows about a deck's archetype, for the other bot in a duel (null if nothing). */
    fun deckKnowledge(choice: DeckChoice?): com.ygoengine.DeckKnowledge? = runCatching {
        choice ?: return null
        val cn = names()
        val arche = com.ygosandbox.app.combos.ReplayKnowledge.archetypeOf(choice.main + choice.extra, cn)
        val replays = if (arche.isBlank()) null
        else com.ygosandbox.app.combos.ReplayKnowledge.of(com.ygosandbox.app.combos.ReplayLearning.database(), arche, cn)?.forEngine()
        // and the choke points the deck's guides name (Analysis tab)
        val guideChokes = guideSummary(choice)?.chokes((choice.main + choice.extra).map { id -> db.find(id)?.id ?: id }.toSet()) { id -> db.find(id)?.id ?: id }.orEmpty()
        when {
            guideChokes.isEmpty() -> replays
            replays == null -> com.ygoengine.DeckKnowledge(guideChokes, 0.0)
            else -> replays.copy(chokes = (replays.chokes + guideChokes).distinct())
        }
    }.getOrNull()

    /** What the deck's guide videos agree on (Analysis tab), or null. */
    private fun guideSummary(choice: DeckChoice?): com.ygosandbox.app.combos.GuideSummary? = runCatching {
        choice ?: return null
        com.ygosandbox.app.combos.GuideViewModel.summaryFor((choice.main + choice.extra).toSet()) { id -> db.find(id)?.id ?: id }
    }.getOrNull()

    /** Deck-guide advice for this deck's bot: starters, extenders, search order, cards to keep. */
    fun guidePriorities(choice: DeckChoice?): com.ygoengine.CardPriorities? = runCatching {
        choice ?: return null
        guideSummary(choice)?.priorities((choice.main + choice.extra).map { id -> db.find(id)?.id ?: id }.toSet()) { id -> db.find(id)?.id ?: id }
    }.getOrNull()

    fun referenceFor(choice: DeckChoice?): com.ygoengine.ReferenceResult? =
        choice?.let { references.load(references.signature(it.main, it.extra)) }

    fun buildReference(deckKey: String, thorough: Boolean) {
        if (refJob?.isActive == true) return
        val choice = deckChoice(deckKey) ?: return
        refJob = keepAwake("Building reference end boards") {
            _ui.update { it.copy(play = it.play.copy(refError = null, refProgress = com.ygoengine.Progress("starting", 0, 0))) }
            try {
                val result = engine().reference(
                    choice.toEngine(::cardName),
                    hands = if (thorough) 30 else 12,
                    iterations = if (thorough) 2000 else 1000,
                    seed = System.currentTimeMillis(),
                    // the deck's best lines also teach the shared move policy
                    policy = policies.load(),
                ) { p -> _ui.update { it.copy(play = it.play.copy(refProgress = p)) } }
                policies.save(result.policyJson)
                if (result.samples.isNotEmpty()) references.save(references.signature(choice.main, choice.extra), result)
                else _ui.update { it.copy(play = it.play.copy(refError = "Stopped before the first hand finished")) }
                _ui.update { it.copy(play = it.play.copy(refRevision = it.play.refRevision + 1)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(play = it.play.copy(refError = "The engine failed: ${e.message ?: e.toString()}")) }
            } finally {
                _ui.update { it.copy(play = it.play.copy(refProgress = null)) }
            }
        }
    }

    fun stopReference() {
        engine?.cancel()
    }

    fun stopSidePlan() {
        engine?.cancel()
    }

    /** A passcode's card name from the downloaded card database (null if unknown). */
    private fun cardName(id: Long): String? = db.find(id)?.name

    // ---------- Duel simulator ----------

    private var liveDuel: LiveDuel? = null
    private var duelJob: Job? = null
    private var humanChoice: CompletableDeferred<Int>? = null
    private val stepRequests = MutableStateFlow(0)

    fun updateDuelSetup(change: (DuelSetup) -> DuelSetup) =
        _ui.update { it.copy(duel = it.duel.copy(setup = change(it.duel.setup), error = null)) }

    private fun updateDuel(change: (DuelUi) -> DuelUi) = _ui.update { it.copy(duel = change(it.duel)) }

    fun startDuel() {
        val setup = _ui.value.duel.setup
        val d1 = deckChoice(setup.deck1)
        val d2 = deckChoice(setup.deck2)
        if (d1 == null || d2 == null) {
            updateDuel { it.copy(error = "Pick both decks") }
            return
        }
        stopDuel()
        // The engine's player 0 always goes first; deck 1 (bottom of the board) may be either.
        val deck1First = when (setup.first) {
            FirstPlayer.ME -> true
            FirstPlayer.THEM -> false
            FirstPlayer.RANDOM -> kotlin.random.Random.nextBoolean()
        }
        val bottom = if (deck1First) 0 else 1
        val humanMode = setup.mode == DuelMode.HUMAN_VS_BOT
        val name1 = if (humanMode) "You" else "Bot 1 (${d1.name})"
        val name2 = if (humanMode) "Bot" else "Bot 2 (${d2.name})"
        val names = if (deck1First) listOf(name1, name2) else listOf(name2, name1)
        val levels = if (deck1First) listOf(setup.level1, setup.level2) else listOf(setup.level2, setup.level1)
        updateDuel {
            it.copy(
                active = true, starting = true, state = null, bottom = bottom,
                human = if (humanMode) bottom else -1, names = names, levels = levels,
                log = listOf(LogEntry(0, "${names[0]} goes first.")), moves = emptyList(), paused = false, thinking = false, error = null,
            )
        }
        CrashReports.note(
            "Duel start: ${names.joinToString(" vs ")} · ${setup.mode} · levels ${levels.joinToString("/")} · " +
                "decks ${d1.name} (${d1.main.size}+${d1.extra.size}) and ${d2.name} (${d2.main.size}+${d2.extra.size})"
        )
        duelJob = viewModelScope.launch {
            try {
                // Each bot uses its deck's combo pieces when that deck has reference boards.
                val lib1 = pieceLibrary(d1)
                val lib2 = pieceLibrary(d2)
                val duel = engine().newDuel(
                    if (deck1First) d1.toEngine(::cardName) else d2.toEngine(::cardName),
                    if (deck1First) d2.toEngine(::cardName) else d1.toEngine(::cardName),
                    libraries = if (deck1First) listOf(lib1, lib2) else listOf(lib2, lib1),
                    // The engine asks the person for their own zone choices.
                    human = if (humanMode) bottom else -1,
                    policy = policies.load(),
                    // what past games say about each deck (where it is stopped, how many plays it has)
                    knowledge = if (deck1First) listOf(deckKnowledge(d1), deckKnowledge(d2)) else listOf(deckKnowledge(d2), deckKnowledge(d1)),
                    // replay choices the engine doesn't understand yet, followed by each bot
                    imitate = if (deck1First) listOf(imitations(d1), imitations(d2)) else listOf(imitations(d2), imitations(d1)),
                    // what each deck's guide videos teach (Analysis tab)
                    priorities = if (deck1First) listOf(guidePriorities(d1), guidePriorities(d2)) else listOf(guidePriorities(d2), guidePriorities(d1)),
                )
                liveDuel = duel
                publishDuelState(duel)
                updateDuel { it.copy(starting = false) }
                playDuel(duel)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashReports.note("Duel stopped by an error: $e")
                CrashReports.record(e, Thread.currentThread().name, fatal = false)
                updateDuel { it.copy(starting = false, error = "The duel stopped: ${e.message ?: e.toString()}") }
            }
        }
    }

    private suspend fun publishDuelState(duel: LiveDuel) {
        val s = duel.state()
        CrashReports.note("Duel: turn ${s.turn}, ${s.phase}, LP ${s.players.joinToString("/") { it.lp.toString() }}" +
            (s.log.lastOrNull()?.let { " · $it" } ?: ""))
        updateDuel { d ->
            val lines = s.log.mapIndexed { i, line ->
                LogEntry(s.logPlayers.getOrElse(i) { -1 }, line.replace("[P0]", d.names[0]).replace("[P1]", d.names[1]))
            }
            d.copy(
                state = s,
                log = (d.log + lines).takeLast(600),
                moves = s.moves,
                activations = s.activations,
                moveBatch = if (s.moves.isEmpty() && s.activations.isEmpty()) d.moveBatch else d.moveBatch + 1,
            )
        }
    }

    /** When the board's current animations end (clock millis); set by the duel screen. */
    @Volatile var duelAnimationsUntil = 0L

    /** Waits until the board has shown the last update's moves and activations (at most a few seconds). */
    private suspend fun awaitDuelAnimations() {
        delay(60) // let the screen pick up the new state and plan its animations
        val limit = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < minOf(duelAnimationsUntil, limit)) delay(40)
    }

    private suspend fun playDuel(duel: LiveDuel) {
        while (true) {
            val d = _ui.value.duel
            val state = d.state ?: return
            val decision = state.decision ?: break
            if (state.ended) break
            if (decision.player == d.human) {
                val choice = CompletableDeferred<Int>()
                humanChoice = choice
                val index = choice.await()
                humanChoice = null
                CrashReports.note("Duel: you choose \"${decision.options.getOrNull(index)?.label ?: index}\" (${decision.type})")
                if (!duel.respond(index)) showMessage("That choice isn't allowed right now")
            } else {
                // Bot: wait while paused (a Step request lets one move through), then the delay.
                val steps = stepRequests.value
                if (_ui.value.duel.paused) {
                    kotlinx.coroutines.flow.combine(_ui, stepRequests) { u, s -> !u.duel.paused || s != steps }.first { it }
                }
                if (!_ui.value.duel.paused) {
                    // Let activations and moving cards finish before the next bot move.
                    awaitDuelAnimations()
                    delay(_ui.value.duel.setup.delayMs)
                }
                updateDuel { it.copy(thinking = true) }
                CrashReports.note("Duel: bot ${decision.player + 1} decides (${decision.type}: ${decision.prompt.take(120)}, ${decision.options.size} options)")
                try {
                    val move = duel.botMove(_ui.value.duel.levels[decision.player])
                    CrashReports.note("Duel: bot ${decision.player + 1} chose \"$move\"")
                } finally {
                    updateDuel { it.copy(thinking = false) }
                }
            }
            publishDuelState(duel)
        }
    }

    /** The person's answer to the current decision (option index). */
    fun chooseDuelOption(index: Int) {
        humanChoice?.complete(index)
    }

    fun pauseDuel(paused: Boolean) = updateDuel { it.copy(paused = paused) }

    /** While paused: let the bot make one move. */
    fun stepDuel() {
        stepRequests.value += 1
    }

    /** Makes a thinking bot decide now. */
    fun hurryBot() {
        liveDuel?.hurryBot()
    }

    /** Ends the current duel and goes back to the setup. */
    fun stopDuel() {
        if (liveDuel != null || duelJob != null) CrashReports.note("Duel ended by the player")
        duelJob?.cancel()
        duelJob = null
        humanChoice?.cancel()
        humanChoice = null
        val d = liveDuel
        liveDuel = null
        if (d != null) viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) { d.close() }
        updateDuel { it.copy(active = false, starting = false, thinking = false, state = null) }
    }

    override fun onCleared() {
        duelJob?.cancel()
        liveDuel?.close()
        liveDuel = null
        engine?.close()
        engine = null
    }

    fun showMessage(text: String) = _ui.update { it.copy(message = text) }
    fun dismissMessage() = _ui.update { it.copy(message = null) }
}
