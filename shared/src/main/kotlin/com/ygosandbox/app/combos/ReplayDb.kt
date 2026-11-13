package com.ygosandbox.app.combos

import com.ygoengine.DeckKnowledge
import com.ygoengine.EngineDeck
import com.ygoengine.VideoLineInput
import com.ygoengine.VideoStepInput
import com.ygoengine.YgoEngine
import com.ygosandbox.app.data.CardDatabase
import com.ygosandbox.app.data.PolicyStore
import com.ygosandbox.app.platform.AppPlatform
import com.ygosandbox.app.work.BackgroundWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ---------------------------------------------------------------- what is kept

/** One turn of one player in a replay: the line they played and how it went. */
data class ReplayTurn(
    val game: String,
    /** 0 or 1, as in the replay. */
    val player: Int,
    /** The player's deck by its most played archetype ("" when none dominates). */
    val archetype: String,
    val turn: Int,
    val goingFirst: Boolean,
    /** The cards that player held at the start (only for the recording player's first turn). */
    val hand: List<Long>,
    val steps: List<ComboStep>,
    val endBoard: List<BoardCard>,
    /** Their activations this turn. */
    val plays: Int,
    /** What the opponent activated during it: the card and the card it answered (0 if none). */
    val interruptions: List<Pair<Long, Long>>,
    /** The opponent's draw / search punishers active during it: "mulcharmy", "droll". */
    val under: List<String>,
    /** The game's result for this player, null when the replay stops early. */
    val won: Boolean?,
    /** The opponent's activations in their next turn (how well this board held), null if there was none. */
    val opponentNextPlays: Int?,
    /** This player lost during the opponent's next turn. */
    val lostNextTurn: Boolean,
    /** The engine's replay of the line (first turns of the recorded player), null until checked. */
    val check: LineCheck? = null,
) {
    val key: String get() = "$game#$turn"

    /**
     * 0..1: how strong the line was. Half the game's result, and half how the board held: the
     * opponent's next turn short (few activations) and not losing in it.
     */
    val performance: Double get() {
        val result = when (won) { true -> 1.0; false -> 0.0; null -> 0.5 }
        val held = when {
            lostNextTurn -> 0.0
            opponentNextPlays == null -> 0.5
            else -> (1.0 - opponentNextPlays / 12.0).coerceIn(0.0, 1.0)
        }
        return 0.5 * result + 0.5 * held
    }
}

data class ReplayGameInfo(
    val id: String,
    val savedAt: Long,
    /** Per player: the deck's archetype. */
    val archetypes: List<String>,
    val first: Int,
    val winner: Int?,
    val recording: Int,
    val turns: Int,
    /** The recorded player's Main + Extra Deck (empty when the replay has none), to replay their turns later. */
    val deck: List<Long> = emptyList(),
    /** Per player: their Main + Extra Deck (empty when not in the replay). */
    val decks: List<List<Long>> = emptyList(),
    /** Read with both players' views (their hands and draws known), when the replay has them. */
    val full: Boolean = false,
    /** Version of the reading ([UniversalReplay.VERSION]); older games are read again from their file. */
    val reader: Int = 1,
) {
    /** The Main + Extra Deck of [player], or empty. */
    fun deckOf(player: Int): List<Long> = decks.getOrNull(player)?.takeIf { it.isNotEmpty() } ?: if (player == recording) deck else emptyList()
}

data class ReplayDatabase(val games: List<ReplayGameInfo>, val turns: List<ReplayTurn>, val unreadable: List<String>) {
    val ids: Set<String> get() = (games.map { it.id } + unreadable).toSet()

    /** Turns the engine can replay (a first turn with the opening hand and the player's deck) and hasn't yet. */
    fun pending(): List<ReplayTurn> {
        val byId = games.associateBy { it.id }
        return turns.filter { t ->
            t.check == null && t.hand.size >= 5 && t.turn == 1 && t.steps.size >= 3 &&
                byId[t.game]?.deckOf(t.player)?.isNotEmpty() == true
        }
    }

    /** Games read by an older version: read again from their file (decks, both players' hands). */
    val outdated: Set<String> get() = games.filter { it.reader < UniversalReplay.VERSION }.map { it.id }.toSet()
}

/** Every analysed YGO Omega replay, for all decks: files/replaydb.json. */
class ReplayDbStore {
    private val file get() = File(AppPlatform.current.filesDir, "replaydb.json")

    fun load(): ReplayDatabase = runCatching { decode(JSONObject(file.readText())) }.getOrDefault(ReplayDatabase(emptyList(), emptyList(), emptyList()))

    fun save(db: ReplayDatabase) {
        val f = file
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(encode(db).toString())
        com.ygosandbox.app.platform.replaceFile(tmp, f)
    }

    private fun JSONArray?.objects() = this?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    private fun JSONArray?.longs() = this?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList()
    private fun JSONArray?.strings() = this?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
    private fun JSONObject.intOrNull(k: String) = if (!has(k) || isNull(k)) null else optInt(k)
    private fun JSONObject.boolOrNull(k: String) = if (!has(k) || isNull(k)) null else optBoolean(k)

    private fun encode(db: ReplayDatabase) = JSONObject().put("version", 1)
        .put("unreadable", JSONArray(db.unreadable))
        .put("games", JSONArray(db.games.map { g ->
            JSONObject().put("id", g.id).put("savedAt", g.savedAt).put("archetypes", JSONArray(g.archetypes))
                .put("first", g.first).put("winner", g.winner ?: JSONObject.NULL).put("recording", g.recording).put("turns", g.turns)
                .put("deck", JSONArray(g.deck)).put("decks", JSONArray(g.decks.map { JSONArray(it) }))
                .put("full", g.full).put("reader", g.reader)
        }))
        .put("turns", JSONArray(db.turns.map { t ->
            JSONObject().put("game", t.game).put("player", t.player).put("archetype", t.archetype).put("turn", t.turn)
                .put("goingFirst", t.goingFirst).put("hand", JSONArray(t.hand))
                .put("steps", JSONArray(t.steps.map { s -> JSONObject().put("action", s.action).put("code", s.code ?: 0L).put("name", s.name).put("detail", s.detail) }))
                .put("endBoard", JSONArray(t.endBoard.map { b -> JSONObject().put("code", b.code).put("name", b.name).put("where", b.where) }))
                .put("plays", t.plays)
                .put("interruptions", JSONArray(t.interruptions.map { (c, on) -> JSONObject().put("code", c).put("on", on) }))
                .put("under", JSONArray(t.under)).put("won", t.won ?: JSONObject.NULL)
                .put("opponentNextPlays", t.opponentNextPlays ?: JSONObject.NULL).put("lostNextTurn", t.lostNextTurn)
                .put("check", t.check?.let { c ->
                    JSONObject().put("verified", c.verified).put("stepsMatched", c.stepsMatched).put("stepsChecked", c.stepsChecked)
                        .put("boardMatch", c.boardMatch).put("problem", c.problem).put("library", c.library ?: JSONObject.NULL)
                        .put("verifiedSteps", c.verifiedSteps)
                } ?: JSONObject.NULL)
        }))

    private fun decode(o: JSONObject) = ReplayDatabase(
        unreadable = o.optJSONArray("unreadable").strings(),
        games = o.optJSONArray("games").objects().map { g ->
            ReplayGameInfo(g.optString("id"), g.optLong("savedAt"), g.optJSONArray("archetypes").strings(), g.optInt("first"),
                g.intOrNull("winner"), g.optInt("recording"), g.optInt("turns"), g.optJSONArray("deck").longs(),
                g.optJSONArray("decks")?.let { a -> (0 until a.length()).map { a.optJSONArray(it).longs() } } ?: emptyList(),
                g.optBoolean("full"), g.optInt("reader", 1))
        },
        turns = o.optJSONArray("turns").objects().map { t ->
            ReplayTurn(
                game = t.optString("game"), player = t.optInt("player"), archetype = t.optString("archetype"), turn = t.optInt("turn"),
                goingFirst = t.optBoolean("goingFirst"), hand = t.optJSONArray("hand").longs(),
                steps = t.optJSONArray("steps").objects().map { s ->
                    ComboStep(null, s.optString("action"), s.optLong("code").takeIf { it != 0L }, s.optString("name"), s.optString("detail"))
                },
                endBoard = t.optJSONArray("endBoard").objects().map { b -> BoardCard(b.optLong("code"), b.optString("name"), b.optString("where")) },
                plays = t.optInt("plays"),
                interruptions = t.optJSONArray("interruptions").objects().map { it.optLong("code") to it.optLong("on") },
                under = t.optJSONArray("under").strings(), won = t.boolOrNull("won"),
                opponentNextPlays = t.intOrNull("opponentNextPlays"), lostNextTurn = t.optBoolean("lostNextTurn"),
                check = t.optJSONObject("check")?.let { c ->
                    LineCheck(c.optBoolean("verified"), c.optInt("stepsMatched"), c.optInt("stepsChecked"), c.optDouble("boardMatch", 0.0),
                        c.optString("problem"), if (c.isNull("library")) null else c.optString("library"), c.optInt("verifiedSteps"))
                },
            )
        },
    )
}

// ---------------------------------------------------------------- reading a replay, for every deck

object UniversalReplay {
    /** 2: both players' views (decks, hands, draws) when the replay has them. */
    const val VERSION = 2

    /** Mulcharmy cards and Droll & Lock Bird, by name: the opponent's draw / search punishers. */
    fun punisher(name: String): String? = when {
        name.startsWith("Mulcharmy") -> "mulcharmy"
        name.startsWith("Droll") -> "droll"
        else -> null
    }

    /** The archetype most of a player's cards belong to (hand traps and generic cards don't decide it). */
    fun archetype(codes: Collection<Long>, cn: CardNames, handTraps: Set<Long>): String =
        codes.map(cn::canonical).filter { it !in handTraps }.map(cn::archetype).filter { it.isNotBlank() }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ""

    /** Both players' turns of one replay, or null when it can't be read. */
    fun read(fileName: String, bytes: ByteArray, cn: CardNames, handTraps: Set<Long>): Pair<ReplayGameInfo, List<ReplayTurn>>? {
        val g = runCatching { OmegaReplay.read(bytes) }.getOrNull() ?: return null
        val id = OmegaImport.id(bytes)
        fun c(x: Long) = cn.canonical(x)
        val arche = (0..1).map { archetype(g.seen[it], cn, handTraps) }
        val decks = (0..1).map { g.decks.getOrNull(it)?.first.orEmpty() }
        val info = ReplayGameInfo(id, OmegaImport.savedAt(fileName, 0L), arche, g.first, g.winner, g.deckOwner, g.turns.size,
            g.deck?.first.orEmpty(), decks, g.fullInfo, VERSION)
        val turns = g.turns.mapIndexed { i, t ->
            val p = t.player
            val mine = t.events.filter { it.player == p }
            val theirs = t.events.filter { it.player != p && it.action == "activate" }
            val next = g.turns.getOrNull(i + 1)?.takeIf { it.player != p }
            ReplayTurn(
                game = id, player = p, archetype = arche[p], turn = t.number, goingFirst = p == g.first,
                // the first player's opening turn: their opening hand, known for the recorded player and,
                // with both views, for the other one too
                hand = if (i == 0 && (p == g.deckOwner || g.fullInfo) && g.openingHands[p].none { it == 0L })
                    g.openingHands[p].map(::c) else emptyList(),
                steps = mine.map { e -> ComboStep(null, e.action, c(e.code), cn.name(e.code), if (e.onOpponentField) "on the opponent's field" else "") },
                endBoard = t.endField[p].map { (code, where) -> BoardCard(c(code), cn.name(code), where) },
                plays = mine.count { it.action == "activate" },
                interruptions = theirs.map { c(it.code) to c(it.chainedTo) },
                under = theirs.mapNotNull { punisher(cn.name(it.code)) }.distinct(),
                won = g.winner?.let { it == p },
                opponentNextPlays = next?.events?.count { it.player == next.player && it.action == "activate" },
                // the game ended in the opponent's next turn, won by them
                lostNextTurn = next != null && i + 2 == g.turns.size && g.winner == next.player,
            )
        }
        return info to turns.filter { it.steps.isNotEmpty() }
    }
}

// ---------------------------------------------------------------- what the engine learns from it

/** What the replays say about one archetype. */
data class ArchetypeKnowledge(
    val archetype: String,
    val games: Int,
    val winsFirst: Int, val lossesFirst: Int,
    val winsSecond: Int, val lossesSecond: Int,
    /** Average activations in a turn, going first (their opening) and overall. */
    val playsFirst: Double,
    val plays: Double,
    /** Their cards the opponents answered most, with how often, and how often the game was lost after. */
    val chokes: List<Choke>,
    /** Their best lines: verified first turns and others, by performance. */
    val bestTurns: List<ReplayTurn>,
    /** Turns played under the opponent's Mulcharmy / Droll, and their average performance. */
    val underTurns: Int,
    val underPerformance: Double,
    val turnsChecked: Int,
    val turnsVerified: Int,
) {
    data class Choke(val code: Long, val name: String, val times: Int, val lostAfter: Int)

    /** For the other player's bot in a duel. */
    fun forEngine(): DeckKnowledge = DeckKnowledge(chokes.take(6).map { it.code }, plays)
}

object ReplayKnowledge {
    fun of(db: ReplayDatabase, archetype: String, cn: CardNames): ArchetypeKnowledge? {
        val turns = db.turns.filter { it.archetype.equals(archetype, ignoreCase = true) }
        if (turns.isEmpty()) return null
        val games = turns.map { it.game }.distinct()
        val perGame = games.mapNotNull { id -> turns.firstOrNull { it.game == id } }
        fun avg(l: List<Int>) = if (l.isEmpty()) 0.0 else l.average()
        val chokeCounts = HashMap<Long, Pair<Int, Int>>()
        for (t in turns) for ((_, on) in t.interruptions) if (on != 0L) {
            // only answers to this deck's own cards (its turn's steps)
            if (t.steps.none { it.code == on }) continue
            val (n, lost) = chokeCounts[on] ?: (0 to 0)
            chokeCounts[on] = (n + 1) to (lost + if (t.won == false) 1 else 0)
        }
        val under = turns.filter { it.under.isNotEmpty() }
        return ArchetypeKnowledge(
            archetype = turns.first().archetype,
            games = games.size,
            winsFirst = perGame.count { it.goingFirst && it.won == true }, lossesFirst = perGame.count { it.goingFirst && it.won == false },
            winsSecond = perGame.count { !it.goingFirst && it.won == true }, lossesSecond = perGame.count { !it.goingFirst && it.won == false },
            playsFirst = avg(turns.filter { it.goingFirst && it.turn == 1 }.map { it.plays }),
            plays = avg(turns.map { it.plays }),
            chokes = chokeCounts.entries.sortedByDescending { it.value.first }
                .map { (code, v) -> ArchetypeKnowledge.Choke(code, cn.name(code), v.first, v.second) },
            bestTurns = turns.filter { it.steps.size >= 3 }.sortedWith(
                compareByDescending<ReplayTurn> { it.check?.verified == true }.thenByDescending { it.performance }),
            underTurns = under.size,
            underPerformance = if (under.isEmpty()) 0.0 else under.map { it.performance }.average(),
            turnsChecked = turns.count { it.check != null },
            turnsVerified = turns.count { it.check?.verified == true },
        )
    }

    /** Every archetype in the replays, most played first. */
    fun all(db: ReplayDatabase, cn: CardNames): List<ArchetypeKnowledge> =
        db.turns.map { it.archetype }.filter { it.isNotBlank() }.distinct()
            .mapNotNull { of(db, it, cn) }.sortedByDescending { it.games }

    /** The archetype of a deck: the one most of its Main + Extra Deck belongs to. */
    fun archetypeOf(cards: List<Long>, cn: CardNames): String = UniversalReplay.archetype(cards, cn, emptySet())

    /**
     * Combo pieces of the verified replay lines that [cards] can play, each weighted by how well
     * its line did (stronger lines are tried first). Null if none.
     */
    fun libraryFor(db: ReplayDatabase, cards: Set<Long>, canonical: (Long) -> Long): String? {
        val deck = cards.map(canonical).toSet()
        val out = JSONArray()
        for (t in db.turns) {
            val text = t.check?.library ?: continue
            val arr = runCatching { JSONArray(text) }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val piece = arr.getJSONObject(i)
                val needs = piece.optJSONArray("needs_hand") ?: JSONArray()
                if ((0 until needs.length()).any { k -> canonical(needs.getJSONObject(k).optLong("code")) !in deck }) continue
                piece.put("count", piece.optInt("count", 1) + (t.performance * 4).toInt())
                if (t.under.isNotEmpty()) piece.put("under", JSONArray(t.under))
                out.put(piece)
            }
        }
        return if (out.length() == 0) null else out.toString()
    }

    /** The games with [archetype] on either side, as matches seen from that side (Replays tab). */
    fun matches(db: ReplayDatabase, archetype: String, cn: CardNames): List<MatchReplay> =
        db.games.mapNotNull { g ->
            val side = g.archetypes.indexOfFirst { it.equals(archetype, ignoreCase = true) }.takeIf { it >= 0 } ?: return@mapNotNull null
            val turns = db.turns.filter { it.game == g.id }.sortedBy { it.turn }
            val date = java.time.Instant.ofEpochMilli(g.savedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
            MatchReplay(
                url = g.id, title = "YGO Omega replay · $date", uploader = "YGO Omega", date = date,
                format = "YGO Omega replay", opponent = g.archetypes.getOrNull(1 - side)?.ifBlank { null } ?: "Unknown deck",
                games = listOf(
                    GameRecord(
                        number = 1, weFirst = g.first == side, won = g.winner?.let { it == side },
                        how = if (g.winner == null) "The replay stops before the end" else "",
                        turns = turns.map { t ->
                            TurnRecord(
                                turn = t.turn, ours = t.player == side,
                                summary = t.steps.groupBy { it.action }.entries.joinToString(" · ") { (a, s) ->
                                    a.replace('_', ' ').replaceFirstChar { it.uppercase() } + ": " +
                                        s.joinToString(", ") { it.name + if (it.detail.isNotEmpty()) " (${it.detail})" else "" }
                                } + if (t.under.isNotEmpty()) " · under " + t.under.joinToString(", ") else "",
                                plays = t.steps.mapNotNull { it.code }.distinct(),
                                endBoard = t.endBoard.map { it.code },
                                interrupts = t.interruptions.map { (c, on) ->
                                    Interrupt(c, cn.name(c), t.player != side, if (on != 0L) cn.name(on) else "")
                                },
                            )
                        },
                        turningPoints = emptyList(),
                    )
                ),
                sideChanges = emptyList(), lessons = emptyList(), reader = Readers.OMEGA,
            )
        }
}

// ---------------------------------------------------------------- the analysis job

/**
 * Analysing the YGO Omega replay folder for every deck: each new replay is read (both players,
 * every turn), and the recorded player's first turns are replayed by the engine, which keeps
 * their combo pieces and learns its move policy from them.
 */
object ReplayLearning {
    data class State(val running: Boolean = false, val progress: String = "", val note: String? = null, val revision: Int = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state
    val store = ReplayDbStore()

    @Volatile private var cache: ReplayDatabase? = null
    fun database(): ReplayDatabase = cache ?: store.load().also { cache = it }

    fun analyze(files: List<Pair<String, ByteArray>>, db: CardDatabase, engine: suspend () -> YgoEngine) {
        if (_state.value.running) return
        _state.update { it.copy(running = true, note = null, progress = "Reading replays") }
        BackgroundWork.launch("replaydb", "Analysing replays") {
            var added = 0
            var checked = 0
            var verified = 0
            try {
                val cn = CardNames(db)
                val handTraps = ComboViewModel.HAND_TRAPS.mapNotNull { cn.resolve(it, emptySet()) }.map { cn.canonical(it) }.toSet()
                var data = database()
                val outdated = data.outdated
                // new replays, and games read by an older version (read again: both players' views)
                val fresh = files.filter { (_, b) -> OmegaImport.id(b).let { it !in data.ids || it in outdated } }
                val again = fresh.map { (_, b) -> OmegaImport.id(b) }.filter { it in outdated }.toSet()
                // the engine's replays already done for those games are kept
                val oldChecks = data.turns.filter { it.game in again && it.check != null }.associateBy({ "${it.key}/${it.player}" }, { it.check })
                val games = ArrayList(data.games.filter { it.id !in again })
                val turns = ArrayList(data.turns.filter { it.game !in again })
                val unreadable = ArrayList(data.unreadable)
                fresh.forEachIndexed { i, (name, bytes) ->
                    _state.update { it.copy(progress = "Reading ${i + 1} of ${fresh.size}") }
                    BackgroundWork.update("replaydb", "Reading ${i + 1} of ${fresh.size}", 0.3f * i / fresh.size)
                    val r = UniversalReplay.read(name, bytes, cn, handTraps)
                    if (r == null) { unreadable += OmegaImport.id(bytes); return@forEachIndexed }
                    games += r.first
                    turns += r.second.map { t -> oldChecks["${t.key}/${t.player}"]?.let { t.copy(check = it) } ?: t }
                    if (r.first.id !in again) added++
                    // saved as it goes: closing the app keeps what was read
                    if (added % 10 == 0) save(ReplayDatabase(games, turns, unreadable))
                }
                // older games whose file wasn't in this run (outside the period, deleted) stay as they
                // were read: not asked for again
                for (i in games.indices) if (games[i].reader < UniversalReplay.VERSION) games[i] = games[i].copy(reader = UniversalReplay.VERSION)
                data = ReplayDatabase(games.toList(), turns.toList(), unreadable.toList())
                save(data)

                // the engine replays every first turn it has the deck and hand for and hasn't checked
                // yet: the new ones, and any left when the app was closed during an earlier run
                val byId = games.associateBy { it.id }
                val todo = data.pending()
                val policies = PolicyStore()
                todo.forEachIndexed { i, t ->
                    _state.update { it.copy(progress = "Engine: line ${i + 1} of ${todo.size}") }
                    BackgroundWork.update("replaydb", "Engine checks line ${i + 1} of ${todo.size}", 0.3f + 0.7f * i / todo.size)
                    val cards = byId.getValue(t.game).deckOf(t.player)
                    val deck = EngineDeck(
                        name = t.archetype.ifBlank { "Replay deck" },
                        main = cards.filter { !cn.isExtraDeck(it) }, extra = cards.filter { cn.isExtraDeck(it) },
                        names = cards.distinct().associateWith { cn.name(it) },
                    )
                    val input = VideoLineInput(
                        id = t.key, hand = t.hand,
                        steps = t.steps.map { VideoStepInput(it.action, it.code ?: 0L, it.detail) },
                        endBoard = t.endBoard.filter { it.onField }.map { it.code },
                        under = t.under,
                    )
                    val check = try {
                        val res = engine().videoLines(deck, listOf(input), seconds = 45.0, policy = policies.load())
                        res.policyJson?.let { policies.save(it) }
                        res.lines.firstOrNull()?.let { l ->
                            LineCheck(l.verified, l.stepsMatched, l.stepsChecked, l.boardMatch, l.problem,
                                if (l.verified || l.verifiedSteps > 0) res.libraryJson else null, l.verifiedSteps, l.stepMatch)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (check != null) {
                        checked++
                        if (check.verified) verified++
                        val cur = database()
                        save(cur.copy(turns = cur.turns.map { if (it.key == t.key && it.player == t.player) it.copy(check = check) else it }))
                    }
                }
                _state.update { it.copy(note = (if (added == 0) "No new games" else "$added new game${if (added == 1) "" else "s"} analysed") +
                    (if (checked > 0) "; the engine replayed $checked first turn${if (checked == 1) "" else "s"}, $verified verified" else "") + ".") }
            } catch (e: CancellationException) {
                _state.update { it.copy(note = "Stopped. $added new games analysed.") }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(note = "Couldn't analyse the replays: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _state.update { it.copy(running = false, progress = "") }
            }
        }
    }

    private fun save(d: ReplayDatabase) {
        // A copy: the reading loop keeps adding to its own lists while the screens read this one
        // (sharing them crashed the Settings screen with a ConcurrentModificationException).
        val snapshot = ReplayDatabase(d.games.toList(), d.turns.toList(), d.unreadable.toList())
        store.save(snapshot)
        cache = snapshot
        _state.update { it.copy(revision = it.revision + 1) }
    }
}
