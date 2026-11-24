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
 * along the way as in self-play: the network's numbers from both sides, with who won, and
 * positions to start self-play games from (both decks and the board as card ids). No card names,
 * players or accounts are uploaded. They go as a replays-*.jsonl.gz file on the engine
 * repository's ml-data release, which the nightly run reads. The app's combo pieces (from replays
 * and videos) and its move policy go there too (app-pieces.json, app-policy.json), so the nightly
 * bots play like the app's. Needs a GitHub token that can write to that repository.
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
    /** The engine in use writes start positions (games sent before it are sent once more). */
    private const val K_SUMMARY = "engine_writes_follow_summary"
    private const val K_PIECES_HASH = "app_pieces_hash"
    private const val K_POLICY_HASH = "app_policy_hash"
    /** The game being re-played (id and file name): still set at the next start means it hung. */
    private const val K_TRYING = "trying_game"
    private const val K_TRYING_NAME = "trying_game_name"

    data class State(
        val running: Boolean = false,
        val note: String? = null,
        val sent: Int = 0,
        /** While it runs: "Re-playing game 12 of 140", and how far along. */
        val progress: String? = null,
        val fraction: Float = 0f,
        /** When the game being re-played now was started (ms), 0 when none. */
        val gameStarted: Long = 0,
    )

    private val _state = MutableStateFlow(State(sent = runCatching { prefs.getInt(K_UPLOADED, 0) }.getOrDefault(0)))
    val state: StateFlow<State> get() = _state

    private val prefs get() = AppPlatform.current.preferences("learning")
    // v2: the first version marked games that failed as done, without sending them
    private val file get() = File(AppPlatform.current.filesDir, "learning_sent_v2.json")
    // v4: sent by an engine that also writes how far it followed each game (and follows more)
    private val file4 get() = File(AppPlatform.current.filesDir, "learning_sent_v4.json")
    // games re-played but not uploaded yet (uploaded 10 at a time): kept across restarts
    private val pendingLines get() = File(AppPlatform.current.filesDir, "learning_pending.jsonl")
    private val pendingIds get() = File(AppPlatform.current.filesDir, "learning_pending_ids.json")
    // games the engine got stuck on (the app was closed while re-playing them): skipped, by file name
    private val skippedFile get() = File(AppPlatform.current.filesDir, "learning_skipped.json")

    /** Replays skipped because the engine got stuck on them (file names). */
    fun skipped(): List<String> = runCatching {
        val a = JSONArray(skippedFile.readText())
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    /** Fine-grained GitHub token with Contents: read and write on Yu-Gi-Oh-Engine. */
    var token: String
        get() = prefs.getString("github_token", "") ?: ""
        set(v) = prefs.edit().putString("github_token", v.filterNot { it.isWhitespace() }).apply()

    var enabled: Boolean
        get() = prefs.getBoolean("share", true)
        set(v) = prefs.edit().putBoolean("share", v).apply()

    // ---------------------------------------------------------------- sent games

    private fun load(f: File = file): Set<String> = runCatching {
        val a = JSONArray(f.readText())
        (0 until a.length()).map { a.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    private fun save(ids: Set<String>, f: File = file) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(JSONArray(ids.toList()).toString())
        com.ygosandbox.app.platform.replaceFile(tmp, f)
    }

    /**
     * Uploads the positions of the [files]' games not sent yet. Returns how many games were sent;
     * nothing happens without a token, when switched off, or while another upload runs.
     */
    suspend fun send(files: List<Pair<String, ByteArray>>, db: CardDatabase, engine: suspend () -> YgoEngine): Int {
        if (!enabled || token.isBlank() || _state.value.running) return 0
        val sent = load().toMutableSet()
        val sent4 = load(file4).toMutableSet()
        // a game still marked as being re-played: the app was closed (or stuck) on it last time;
        // it is set aside so one replay can't hold up all the others
        prefs.getString(K_TRYING, null)?.takeIf { it.isNotBlank() }?.let { stuck ->
            val name = prefs.getString(K_TRYING_NAME, null) ?: stuck
            sent += stuck
            sent4 += stuck
            save(sent)
            save(sent4, file4)
            runCatching { skippedFile.writeText(JSONArray((skipped() + name).distinct().takeLast(50)).toString()) }
            prefs.edit().remove(K_TRYING).remove(K_TRYING_NAME).apply()
        }
        // games re-played last time but not uploaded yet: they go with the next upload
        val carried = load(pendingIds)
        sent += carried
        // games sent before the engine wrote its follow summary are sent once more (it also
        // follows more of them; the nightly run keeps a game's newest upload only); until the
        // engine is known to write it, one such game per run checks whether it does
        val fresh = files.filter { (_, b) -> OmegaImport.id(b) !in sent }
        val again = files.filter { (_, b) -> OmegaImport.id(b).let { it in sent && it !in sent4 && it !in carried } }
        val todo = fresh + if (prefs.getBoolean(K_SUMMARY, false)) again else again.take(1)
        if (todo.isEmpty() && carried.isEmpty()) {
            runCatching { shareLearned() }
            return 0
        }
        _state.update { it.copy(running = true, note = null, progress = "Getting ready", fraction = 0f) }
        var games = 0
        var failed = 0
        var firstError: String? = null
        val lines = StringBuilder(runCatching { pendingLines.readText() }.getOrDefault(""))
        val waiting = ArrayList<String>(carried)  // games in [lines], marked done once uploaded
        var inBatch = carried.size
        // what was re-played is kept on disk at once, so a restart doesn't re-play it again
        fun keep() {
            runCatching {
                pendingLines.writeText(lines.toString())
                save(waiting.toSet(), pendingIds)
            }
        }
        suspend fun flush() {
            if (waiting.isEmpty()) return
            if (lines.isNotEmpty()) upload(gzip(lines.toString().toByteArray()))
            runCatching { pendingLines.delete(); pendingIds.delete() }
            prefs.edit().putInt(K_UPLOADED, prefs.getInt(K_UPLOADED, 0) + inBatch).apply()
            sent += waiting
            save(sent)
            if (prefs.getBoolean(K_SUMMARY, false)) {
                sent4 += waiting
                save(sent4, file4)
            }
            lines.setLength(0)
            waiting.clear()
            inBatch = 0
            _state.update { it.copy(sent = prefs.getInt(K_UPLOADED, 0)) }
        }
        try {
            todo.forEachIndexed { i, (name, bytes) ->
                _state.update {
                    it.copy(progress = "Re-playing game ${i + 1} of ${todo.size} by the rules", fraction = i.toFloat() / todo.size,
                        gameStarted = System.currentTimeMillis())
                }
                val id = OmegaImport.id(bytes)
                if (id in carried) return@forEachIndexed
                prefs.edit().putString(K_TRYING, id).putString(K_TRYING_NAME, name).apply()
                try {
                    val positions = withContext(Dispatchers.Default) { positions(bytes, db, engine(), id) }
                    if (positions.contains("\"kind\":\"follow\"") && !prefs.getBoolean(K_SUMMARY, false))
                        prefs.edit().putBoolean(K_SUMMARY, true).apply()
                    // already sent, and this engine adds nothing new: nothing to send again
                    val skip = id in sent && !prefs.getBoolean(K_SUMMARY, false)
                    // a game with no winner (or that couldn't be followed) is done too: nothing to send
                    if (!skip) waiting += id
                    if (positions.isNotEmpty() && !skip) {
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
                prefs.edit().remove(K_TRYING).remove(K_TRYING_NAME).apply()
                keep()
                if (inBatch >= BATCH) flush()
            }
            flush()
            runCatching { shareLearned() }
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
            _state.update { it.copy(running = false, progress = null, fraction = 0f, gameStarted = 0, sent = prefs.getInt(K_UPLOADED, 0)) }
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
        // how far it followed the game (an engine that reports where it stopped): what to fix next
        val summary = if (r.stopTurn >= 0 && r.turns > 0) JSONObject()
            .put("kind", "follow").put("game", "omega-" + hash(id)).put("turns", r.turns).put("turns_followed", r.turnsFollowed)
            .put("events", r.events).put("followed", r.followed).put("complete", r.complete).put("winner", r.winner)
            .put("stopped", r.stopped).put("stop_turn", r.stopTurn).put("stop_next", r.stopNext).toString() + "\n" else ""
        val positions = if (r.winner == 0 || r.winner == 1) r.jsonl else ""
        return if (positions.isEmpty() && summary.isEmpty()) "" else positions.trimEnd('\n').let { if (it.isEmpty()) it else it + "\n" } + summary
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

    // ---------------------------------------------------------------- pieces and policy

    /**
     * The app's combo pieces (from replay turns and video lines, each with how well its line did)
     * and its move policy, for the nightly bots: uploaded as app-pieces.json / app-policy.json
     * when they changed since the last upload.
     */
    private suspend fun shareLearned() {
        val pieces = JSONArray()
        for (t in com.ygosandbox.app.combos.ReplayLearning.database().turns) {
            val text = t.check?.library ?: continue
            val arr = runCatching { JSONArray(text) }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val piece = arr.getJSONObject(i)
                piece.put("count", piece.optInt("count", 1) + (t.performance * 4).toInt())
                if (t.under.isNotEmpty()) piece.put("under", JSONArray(t.under))
                pieces.put(piece)
            }
        }
        com.ygosandbox.app.combos.ComboStore().all().flatMap { it.videos }.mapNotNull { it.check?.library }.forEach { text ->
            val arr = runCatching { JSONArray(text) }.getOrNull() ?: return@forEach
            for (i in 0 until arr.length()) pieces.put(arr.get(i))
        }
        if (pieces.length() > 0) replaceIfChanged("app-pieces.json", pieces.toString(), K_PIECES_HASH)
        com.ygosandbox.app.data.PolicyStore().load()?.let { replaceIfChanged("app-policy.json", it, K_POLICY_HASH) }
    }

    private suspend fun replaceIfChanged(name: String, text: String, key: String) = withContext(Dispatchers.IO) {
        val h = hash(text)
        if (prefs.getString(key, "") == h) return@withContext
        val release = releaseId()
        // the release keeps one file of each name: the old one goes first
        val (lc, lt) = request("GET", "https://api.github.com/repos/$REPO/releases/$release/assets?per_page=100", null, null)
        if (lc in 200..299) {
            val assets = JSONArray(lt)
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == name)
                    request("DELETE", "https://api.github.com/repos/$REPO/releases/assets/${a.getLong("id")}", null, null)
            }
        }
        val (code, body) = request("POST", "https://uploads.github.com/repos/$REPO/releases/$release/assets?name=$name",
            text.toByteArray(), "application/json")
        if (code !in 200..299) throw IllegalStateException(problem(code, body))
        prefs.edit().putString(key, h).apply()
    }

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
