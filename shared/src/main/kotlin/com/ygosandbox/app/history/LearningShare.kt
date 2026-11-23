package com.ygosandbox.app.history

import com.ygoengine.DuelState
import com.ygoengine.YgoEngine
import com.ygoengine.ZoneCard
import com.ygosandbox.app.combos.OmegaImport
import com.ygosandbox.app.combos.OmegaReplay
import com.ygosandbox.app.data.CardDatabase
import com.ygosandbox.app.platform.AppPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * Sends the player's YGO Omega replays to the engine's learning (the value network trained
 * nightly on GitHub, tools/ml in the engine repository). Each finished game becomes training
 * positions: the board after the actions of every Main Phase, rebuilt from the replay, turned by
 * the engine into the network's numbers from both sides, with who won. Only those numbers are
 * uploaded (no card names, players or accounts), as a replays-*.jsonl.gz file on the engine
 * repository's ml-data release, which the nightly run reads. Needs a GitHub token that can write
 * to that repository.
 */
object LearningShare {
    private const val REPO = "0megaXP/Yu-Gi-Oh-Engine"
    private const val TAG = "ml-data"
    /** Boards kept per turn (they are much alike; self-play keeps 6). */
    private const val PER_TURN = 6
    /** Games per uploaded file. */
    private const val BATCH = 200

    data class State(val running: Boolean = false, val note: String? = null, val sent: Int = 0)

    private val _state = MutableStateFlow(State(sent = runCatching { prefs.getInt("uploaded", 0) }.getOrDefault(0)))
    val state: StateFlow<State> get() = _state

    private val prefs get() = AppPlatform.current.preferences("learning")
    private val file get() = File(AppPlatform.current.filesDir, "learning_sent.json")

    /** Fine-grained GitHub token with Contents: read and write on Yu-Gi-Oh-Engine. */
    var token: String
        get() = prefs.getString("github_token", "") ?: ""
        set(v) = prefs.edit().putString("github_token", v.filterNot { it.isWhitespace() }).apply()

    var enabled: Boolean
        get() = prefs.getBoolean("share", true)
        set(v) = prefs.edit().putBoolean("share", v).apply()

    // ---------------------------------------------------------------- sent games

    private fun load(): Set<String> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    private fun save(ids: Set<String>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray(ids.toList()).toString())
        com.ygosandbox.app.platform.replaceFile(tmp, file)
    }

    /**
     * Uploads the positions of the [files]' games not sent yet. Returns how many games were sent;
     * nothing happens without a token, when switched off, or while another upload runs.
     */
    suspend fun send(files: List<Pair<String, ByteArray>>, db: CardDatabase, engine: suspend () -> YgoEngine): Int {
        if (!enabled || token.isBlank() || _state.value.running) return 0
        val sent = load().toMutableSet()
        val todo = files.filter { (_, b) -> OmegaImport.id(b) !in sent }
        if (todo.isEmpty()) return 0
        _state.update { it.copy(running = true, note = null) }
        var games = 0
        var uploadedBefore = 0
        try {
            for (chunk in todo.chunked(BATCH)) {
                val lines = StringBuilder()
                val ids = ArrayList<String>()
                for ((_, bytes) in chunk) {
                    val id = OmegaImport.id(bytes)
                    // an unreadable or unfinished game is marked too, so it isn't tried every time
                    ids += id
                    val positions = runCatching { withContext(Dispatchers.Default) { positions(bytes, db, engine(), id) } }.getOrNull() ?: continue
                    if (positions.isNotEmpty()) {
                        lines.append(positions)
                        games++
                    }
                }
                if (lines.isNotEmpty()) upload(gzip(lines.toString().toByteArray()))
                prefs.edit().putInt("uploaded", prefs.getInt("uploaded", 0) + games - uploadedBefore).apply()
                uploadedBefore = games
                sent += ids
                save(sent)
            }
            _state.update { it.copy(note = if (games == 0) null else "$games game${if (games == 1) "" else "s"} sent to the engine's learning.") }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(note = "Couldn't send replays to the engine's learning: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            _state.update { it.copy(running = false, sent = prefs.getInt("uploaded", 0)) }
        }
        return games
    }

    // ---------------------------------------------------------------- positions

    /** The game's training positions as JSON lines, or "" when it has no winner. */
    private suspend fun positions(bytes: ByteArray, db: CardDatabase, engine: YgoEngine, id: String): String {
        val steps = ReplayPlayback.build(bytes, db, listOf("Player 1", "Player 2"))
        val last = steps.lastOrNull()?.state ?: return ""
        val winner = last.winner
        if (winner != 0 && winner != 1) return ""
        // the boards: every Main Phase action, a few per turn spread over it (the last one included)
        val boards = steps.map { it.state }.filter { it.phase.startsWith("Main Phase") }
            .groupBy { it.turn }.values.flatMap { turn ->
                if (turn.size <= PER_TURN) turn
                else (0 until PER_TURN).map { k -> turn[(turn.size - 1) * (k + 1) / PER_TURN] }
            }
        if (boards.isEmpty()) return ""
        val raw = OmegaReplay.raw(bytes)
        val decks = JSONArray((0..1).map { p -> deckJson(raw.decks.getOrNull(p), steps.map { it.state }, p, db) })
        val req = JSONObject()
            .put("decks", decks)
            .put("winner", winner)
            .put("finished", true)
            // only a hash of the game: it keeps a game's positions together (held out as a whole)
            .put("game", "omega-" + hash(id))
            .put("boards", JSONArray(boards.map(::boardJson)))
        return engine.replayPositions(req).first
    }

    /** A player's deck from the replay, or the cards seen of it when the replay doesn't keep it. */
    private fun deckJson(deck: Pair<List<Long>, List<Long>>?, states: List<DuelState>, p: Int, db: CardDatabase): JSONObject {
        val cards = deck?.first ?: states.flatMap { s ->
            val ps = s.players[p]
            ps.hand.map { it.code } + ps.graveyard.map { it.code } + ps.banished.map { it.code } +
                ps.monsters.mapNotNull { it?.code } + ps.spells.mapNotNull { it?.code }
        }.filter { it != 0L }.distinct()
        val (extra, main) = cards.partition { db.find(it)?.isExtraDeck == true }
        return JSONObject().put("main", JSONArray(main)).put("extra", JSONArray(extra))
    }

    private fun boardJson(s: DuelState): JSONObject {
        fun zone(cards: List<ZoneCard?>, monster: Boolean) = JSONArray(cards.mapIndexedNotNull { i, c ->
            c ?: return@mapIndexedNotNull null
            val pos = when {
                c.position == "set" -> if (monster) 0x8 else 0xA
                c.position == "def" -> 0x4
                else -> 0x1
            }
            JSONObject().put("c", c.code).put("p", pos).put("s", i)
        })
        return JSONObject()
            .put("turn", s.turn)
            .put("turn_player", s.turnPlayer)
            .put("players", JSONArray(s.players.map { p ->
                JSONObject()
                    .put("lp", p.lp)
                    .put("hand", JSONArray(p.hand.map { it.code }))
                    .put("grave", JSONArray(p.graveyard.map { it.code }))
                    .put("banished", JSONArray(p.banished.map { it.code }))
                    .put("deck", p.deckCount)
                    .put("extra", p.extra.size)
                    .put("mzone", zone(p.monsters, true))
                    .put("szone", zone(p.spells, false))
            }))
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    private fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(b) } }.toByteArray()

    // ---------------------------------------------------------------- GitHub

    private suspend fun upload(gz: ByteArray) = withContext(Dispatchers.IO) {
        val release = releaseId()
        val name = "replays-${System.currentTimeMillis()}-${hash(java.util.UUID.randomUUID().toString()).take(6)}.jsonl.gz"
        val (code, text) = request("POST", "https://uploads.github.com/repos/$REPO/releases/$release/assets?name=$name", gz, "application/gzip")
        if (code !in 200..299) throw IllegalStateException(problem(code, text))
    }

    /** The ml-data release (made here when the nightly run hasn't made it yet). */
    private fun releaseId(): Long {
        val (code, text) = request("GET", "https://api.github.com/repos/$REPO/releases/tags/$TAG", null, null)
        if (code in 200..299) return JSONObject(text).getLong("id")
        if (code != 404) throw IllegalStateException(problem(code, text))
        val body = JSONObject().put("tag_name", TAG).put("name", "Learning data").put("make_latest", "false")
            .put("body", "Self-play positions of the nightly Learn pipeline (tools/ml). Not an app release.")
        val (c2, t2) = request("POST", "https://api.github.com/repos/$REPO/releases", body.toString().toByteArray(), "application/json")
        if (c2 !in 200..299) throw IllegalStateException(problem(c2, t2))
        return JSONObject(t2).getLong("id")
    }

    private fun problem(code: Int, text: String): String {
        val msg = runCatching { JSONObject(text).optString("message") }.getOrDefault("").ifBlank { text.take(160) }
        return when (code) {
            401 -> "GitHub rejected the token"
            403, 404 -> "the token can't write to Yu-Gi-Oh-Engine (it needs Contents: read and write) — $msg"
            else -> "HTTP $code: $msg"
        }
    }

    private fun request(method: String, url: String, body: ByteArray?, type: String?): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 120_000
        conn.requestMethod = method
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("User-Agent", "YGOSandbox")
        return try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", type ?: "application/octet-stream")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            code to ((if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }
}
