package com.ygosandbox.app.history

import com.ygosandbox.app.combos.CardNames
import com.ygosandbox.app.combos.ComboViewModel
import com.ygosandbox.app.combos.OmegaImport
import com.ygosandbox.app.combos.OmegaReplay
import com.ygosandbox.app.combos.UniversalReplay
import com.ygosandbox.app.data.CardDatabase
import com.ygosandbox.app.platform.AppPlatform
import com.ygosandbox.app.work.BackgroundWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One game from a YGO Omega replay, seen from the player who saved it ("you"). */
data class HistoryGame(
    val id: String,
    /** When the replay was saved (ms). */
    val savedAt: Long,
    /** The two players' Omega account ids (0 when missing). */
    val players: List<Long>,
    /** Which player saved the replay: you. */
    val me: Int,
    /** Per player: the deck's archetype ("" when it can't tell). */
    val archetypes: List<String>,
    val first: Int,
    /** The winner (0 or 1), or null when the replay stops before the end. */
    val winner: Int?,
    /** 0 surrender or time, 1 Life Points, 2 deck out, other: a card's effect. */
    val winReason: Int,
    val turns: Int,
    /** Your opening hand. */
    val myHand: List<Long>,
    /** Cards the opponent played or revealed. */
    val theirCards: List<Long>,
    /** The replay's file name in the replay folder (to watch it), "" when not known yet. */
    val file: String = "",
) {
    val won: Boolean? get() = winner?.let { it == me }
    val wentFirst: Boolean get() = first == me
    val myDeck: String get() = archetypes.getOrElse(me) { "" }
    val theirDeck: String get() = archetypes.getOrElse(1 - me) { "" }
    val opponentId: Long get() = players.getOrElse(1 - me) { 0L }

    val how: String get() = when {
        winner == null -> "replay stops before the end"
        winReason == 0 -> if (won == true) "opponent surrendered or timed out" else "surrendered or timed out"
        winReason == 1 -> "Life Points reached 0"
        winReason == 2 -> "deck out"
        else -> "won by a card's effect"
    }
}

/** The games of one match (a Best of 3, or a single game when no other game goes with it). */
data class HistoryMatch(val games: List<HistoryGame>) {
    val start: Long get() = games.first().savedAt
    val wins: Int get() = games.count { it.won == true }
    val losses: Int get() = games.count { it.won == false }
    /** True won, false lost, null a draw or unfinished. */
    val won: Boolean? get() = when {
        wins > losses -> true
        losses > wins -> false
        else -> null
    }
    val myDeck: String get() = common(games.map { it.myDeck })
    val theirDeck: String get() = common(games.map { it.theirDeck })

    private fun common(names: List<String>) =
        names.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ""
}

/**
 * Match history: every game in the YGO Omega replay folder, grouped into Best of 3 matches.
 * Read straight from the replays (no engine), kept in files/matchhistory.json.
 */
object MatchHistory {
    data class State(val running: Boolean = false, val progress: String = "", val note: String? = null, val revision: Int = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val file get() = File(AppPlatform.current.filesDir, "matchhistory.json")

    private class Data(val games: List<HistoryGame>, val unreadable: List<String>)

    @Volatile private var cache: Data? = null

    /** The folder is read once per app start when the screen opens; Refresh reads it again. */
    @Volatile var readThisSession = false

    private fun data(): Data = cache ?: load().also { cache = it }

    /** Every game read so far, oldest first. */
    fun games(): List<HistoryGame> = data().games

    /** The games grouped into matches, newest first. */
    fun matches(): List<HistoryMatch> = group(games()).reversed()

    /** Replay ids already read (or that couldn't be read): not read again. */
    fun known(): Set<String> = data().let { d -> d.games.map { it.id }.toSet() + d.unreadable }

    /**
     * Omega saves each game of a match as its own replay. Games of one Best of 3 are: the same two
     * players, saved one after another (each within [gapMs] of the last), at most 3 games, and
     * none after a player's second win.
     */
    fun group(games: List<HistoryGame>, gapMs: Long = 60 * 60_000L): List<HistoryMatch> {
        val out = ArrayList<HistoryMatch>()
        var cur = ArrayList<HistoryGame>()
        for (g in games.sortedBy { it.savedAt }) {
            val last = cur.lastOrNull()
            val wins = cur.mapNotNull { it.winner }.groupingBy { it }.eachCount()
            val same = last != null && g.players.size == 2 && 0L !in g.players && g.players.toSet() == last.players.toSet() &&
                g.savedAt > 0 && last.savedAt > 0 && g.savedAt - last.savedAt in 0..gapMs &&
                cur.size < 3 && wins.values.none { it >= 2 }
            if (!same && cur.isNotEmpty()) {
                out += HistoryMatch(cur)
                cur = ArrayList()
            }
            // the same account is "you" in every game of a match, even if the saved side changes
            cur += if (last != null && same && g.players.getOrNull(g.me) != last.players.getOrNull(last.me)) {
                val me = g.players.indexOf(last.players.getOrNull(last.me)).takeIf { it >= 0 } ?: g.me
                g.copy(me = me)
            } else g
        }
        if (cur.isNotEmpty()) out += HistoryMatch(cur)
        return out
    }

    /**
     * Reads the replays of [files] (name to contents, the whole folder) not read yet, and notes
     * the file names of games read before they were kept.
     */
    fun refresh(files: List<Pair<String, ByteArray>>, db: CardDatabase) {
        if (_state.value.running) return
        val names = files.associate { (n, b) -> OmegaImport.id(b) to n }
        data().let { d ->
            if (d.games.any { it.file.isEmpty() && it.id in names })
                save(Data(d.games.map { g -> if (g.file.isEmpty()) names[g.id]?.let { g.copy(file = it) } ?: g else g }, d.unreadable))
        }
        _state.update { it.copy(running = true, note = null, progress = "Reading replays") }
        BackgroundWork.launch("matchhistory", "Reading the match history") {
            var added = 0
            try {
                val known = known()
                val fresh = files.filter { (_, b) -> OmegaImport.id(b) !in known }
                if (fresh.isNotEmpty()) {
                    val cn = CardNames(db)
                    val handTraps = ComboViewModel.HAND_TRAPS.mapNotNull { cn.resolve(it, emptySet()) }.map { cn.canonical(it) }.toSet()
                    val games = ArrayList(data().games)
                    val unreadable = ArrayList(data().unreadable)
                    fresh.forEachIndexed { i, (name, bytes) ->
                        _state.update { it.copy(progress = "Reading ${i + 1} of ${fresh.size}") }
                        BackgroundWork.update("matchhistory", "Reading ${i + 1} of ${fresh.size}", i.toFloat() / fresh.size)
                        val g = read(name, bytes, cn, handTraps)
                        if (g == null) unreadable += OmegaImport.id(bytes) else { games += g; added++ }
                        if ((i + 1) % 25 == 0) save(Data(games.toList(), unreadable.toList()))
                    }
                    save(Data(games.sortedBy { it.savedAt }, unreadable.toList()))
                }
                _state.update { it.copy(note = if (added == 0) "No new games." else "$added new game${if (added == 1) "" else "s"} added.") }
            } catch (e: CancellationException) {
                _state.update { it.copy(note = "Stopped. $added new games added.") }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(note = "Couldn't read the replays: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _state.update { it.copy(running = false, progress = "") }
            }
        }
    }

    private fun read(name: String, bytes: ByteArray, cn: CardNames, handTraps: Set<Long>): HistoryGame? {
        val g = runCatching { OmegaReplay.read(bytes) }.getOrNull() ?: return null
        if (g.turns.isEmpty()) return null
        val me = g.deckOwner
        return HistoryGame(
            id = OmegaImport.id(bytes),
            savedAt = OmegaImport.savedAt(name, 0L),
            players = g.players,
            me = me,
            archetypes = (0..1).map { UniversalReplay.archetype(g.seen[it], cn, handTraps) },
            first = g.first,
            winner = g.winner,
            winReason = g.winReason,
            turns = g.turns.size,
            file = name,
            myHand = g.openingHands.getOrNull(me).orEmpty().map(cn::canonical),
            theirCards = g.seen.getOrNull(1 - me).orEmpty().map(cn::canonical).distinct().take(40),
        )
    }

    /** The replay of [game] from the replay folder, or null when it isn't there any more. */
    suspend fun bytesOf(game: HistoryGame): ByteArray? {
        val platform = AppPlatform.current
        val folder = platform.preferences("replays").getString("omega_folder", null) ?: platform.omegaReplays?.path ?: return null
        val named = if (game.file.isNotEmpty()) platform.readFolder(folder, ".bytes") { n, _ -> n == game.file } else emptyList()
        named.firstOrNull { OmegaImport.id(it.second) == game.id }?.let { return it.second }
        // renamed or moved: look through the whole folder
        return platform.readFolder(folder, ".bytes") { _, _ -> true }.firstOrNull { OmegaImport.id(it.second) == game.id }?.second
    }

    fun game(id: String): HistoryGame? = games().firstOrNull { it.id == id }

    // ---------------------------------------------------------------- file

    private fun save(d: Data) {
        val f = file
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(encode(d).toString())
        com.ygosandbox.app.platform.replaceFile(tmp, f)
        cache = d
        _state.update { it.copy(revision = it.revision + 1) }
    }

    private fun load(): Data = runCatching { decode(JSONObject(file.readText())) }.getOrDefault(Data(emptyList(), emptyList()))

    private fun encode(d: Data) = JSONObject().put("version", 1)
        .put("unreadable", JSONArray(d.unreadable))
        .put("games", JSONArray(d.games.map { g ->
            JSONObject().put("id", g.id).put("savedAt", g.savedAt).put("players", JSONArray(g.players)).put("me", g.me)
                .put("archetypes", JSONArray(g.archetypes)).put("first", g.first).put("winner", g.winner ?: JSONObject.NULL)
                .put("winReason", g.winReason).put("turns", g.turns)
                .put("myHand", JSONArray(g.myHand)).put("theirCards", JSONArray(g.theirCards)).put("file", g.file)
        }))

    private fun decode(o: JSONObject): Data {
        fun JSONArray?.longs() = this?.let { a -> (0 until a.length()).map { a.optLong(it) } } ?: emptyList()
        fun JSONArray?.strings() = this?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        val games = o.optJSONArray("games")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty().map { g ->
            HistoryGame(
                id = g.optString("id"), savedAt = g.optLong("savedAt"), players = g.optJSONArray("players").longs(),
                me = g.optInt("me"), archetypes = g.optJSONArray("archetypes").strings(), first = g.optInt("first"),
                winner = if (g.isNull("winner")) null else g.optInt("winner"), winReason = g.optInt("winReason"),
                turns = g.optInt("turns"), myHand = g.optJSONArray("myHand").longs(), theirCards = g.optJSONArray("theirCards").longs(),
                file = g.optString("file"),
            )
        }
        return Data(games, o.optJSONArray("unreadable").strings())
    }
}
