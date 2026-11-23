package com.ygosandbox.app.history

import com.ygoengine.YgoEngine
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
 * nightly on GitHub, tools/ml in the engine repository). The engine follows each finished game
 * in the real rules core (both players' every action, any phase) and takes training positions
 * along the way as in self-play: the network's numbers from both sides, with who won. Only those numbers are
 * uploaded (no card names, players or accounts), as a replays-*.jsonl.gz file on the engine
 * repository's ml-data release, which the nightly run reads. Needs a GitHub token that can write
 * to that repository.
 */
object LearningShare {
    private const val REPO = "0megaXP/Yu-Gi-Oh-Engine"
    private const val TAG = "ml-data"
    /** Positions kept per turn (they are much alike; self-play keeps 6). */
    private const val PER_TURN = 6
    /** Games per uploaded file (the counter moves after each file). */
    private const val BATCH = 10
    private const val K_UPLOADED = "uploaded_v2"
    private const val K_READ = "games_read_v2"
    private const val K_COMPLETE = "games_complete_v2"
    private const val K_EVENTS = "events_v2"
    private const val K_FOLLOWED = "events_followed_v2"

    data class State(
        val running: Boolean = false,
        val note: String? = null,
        val sent: Int = 0,
        /** While it runs: "Re-playing game 12 of 140", and how far along. */
        val progress: String? = null,
        val fraction: Float = 0f,
    )

    private val _state = MutableStateFlow(State(sent = runCatching { prefs.getInt(K_UPLOADED, 0) }.getOrDefault(0)))
    val state: StateFlow<State> get() = _state

    private val prefs get() = AppPlatform.current.preferences("learning")
    // v2: the first version marked games that failed as done, without sending them
    private val file get() = File(AppPlatform.current.filesDir, "learning_sent_v2.json")

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
        _state.update { it.copy(running = true, note = null, progress = "Getting ready", fraction = 0f) }
        var games = 0
        var failed = 0
        var firstError: String? = null
        val lines = StringBuilder()
        val waiting = ArrayList<String>()  // games in [lines], marked done once uploaded
        var inBatch = 0
        suspend fun flush() {
            if (waiting.isEmpty()) return
            if (lines.isNotEmpty()) upload(gzip(lines.toString().toByteArray()))
            prefs.edit().putInt(K_UPLOADED, prefs.getInt(K_UPLOADED, 0) + inBatch).apply()
            sent += waiting
            save(sent)
            lines.setLength(0)
            waiting.clear()
            inBatch = 0
            _state.update { it.copy(sent = prefs.getInt(K_UPLOADED, 0)) }
        }
        try {
            todo.forEachIndexed { i, (name, bytes) ->
                _state.update { it.copy(progress = "Re-playing game ${i + 1} of ${todo.size} by the rules", fraction = i.toFloat() / todo.size) }
                val id = OmegaImport.id(bytes)
                try {
                    val positions = withContext(Dispatchers.Default) { positions(bytes, db, engine(), id) }
                    // a game with no winner (or that couldn't be followed) is done too: nothing to send
                    waiting += id
                    if (positions.isNotEmpty()) {
                        lines.append(positions)
                        games++
                        inBatch++
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // not marked done: tried again next time
                    failed++
                    if (firstError == null) firstError = "$name: ${e.message ?: e.javaClass.simpleName}"
                }
                if (inBatch >= BATCH) flush()
            }
            flush()
            _state.update {
                it.copy(note = listOfNotNull(
                    if (games > 0) "$games game${if (games == 1) "" else "s"} sent to the engine's learning." else "No new finished games to send.",
                    if (failed > 0) "$failed couldn't be re-played (first: $firstError)" else null,
                ).joinToString(" "))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(note = "Couldn't send replays to the engine's learning: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            _state.update { it.copy(running = false, progress = null, fraction = 0f, sent = prefs.getInt(K_UPLOADED, 0)) }
        }
        return games
    }

    // ---------------------------------------------------------------- positions

    /**
     * The game's training positions as JSON lines ("" when it has no winner or couldn't be
     * followed at all): the engine re-plays the replay in the real rules core and takes them
     * along the way, as it does in self-play.
     */
    private suspend fun positions(bytes: ByteArray, db: CardDatabase, engine: YgoEngine, id: String): String {
        val raw = OmegaReplay.raw(bytes)
        // the lists the replay keeps, main and Extra Deck apart (its deck codes mix them)
        val decks = (0..1).map { p ->
            raw.decks.getOrNull(p)?.first?.takeIf { it.isNotEmpty() }?.let { cards ->
                val (extra, main) = cards.partition { db.find(it)?.isExtraDeck == true }
                main to extra
            }
        }
        // only a hash of the game: it keeps a game's positions together (held out as a whole)
        val r = engine.followReplay(raw.messages, decks, "omega-" + hash(id), PER_TURN)
        if (r.events > 0) {
            val e = prefs.edit()
            e.putInt(K_READ, prefs.getInt(K_READ, 0) + 1)
            if (r.complete) e.putInt(K_COMPLETE, prefs.getInt(K_COMPLETE, 0) + 1)
            e.putLong(K_EVENTS, prefs.getLong(K_EVENTS, 0) + r.events)
            e.putLong(K_FOLLOWED, prefs.getLong(K_FOLLOWED, 0) + r.followed)
            e.apply()
        }
        return if (r.winner == 0 || r.winner == 1) r.jsonl else ""
    }

    /** How well the engine could follow the replays so far, for Settings. */
    fun followSummary(): String? {
        val read = prefs.getInt(K_READ, 0)
        if (read == 0) return null
        val full = prefs.getInt(K_COMPLETE, 0)
        val ev = prefs.getLong(K_EVENTS, 0)
        val ok = prefs.getLong(K_FOLLOWED, 0)
        val share = if (ev > 0) (100 * ok / ev).toInt() else 0
        return "The engine re-played $full of $read game${if (read == 1) "" else "s"} to the end by the rules; " +
            "$share% of everything the replays show was reproduced (games are kept up to where they could be followed)."
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
