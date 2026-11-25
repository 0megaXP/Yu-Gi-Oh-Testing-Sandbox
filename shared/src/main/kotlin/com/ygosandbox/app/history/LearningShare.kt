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
 * nightly on GitHub, tools/ml in the engine repository): each game's moves (the replay's game
 * messages, card ids) and the decks it keeps, without the replay's header (no players, accounts
 * or settings). They go as inputs-*.jsonl.gz files on the engine repository's ml-data release;
 * the nightly run follows every game in the real rules core with its newest engine and takes
 * training positions along the way as in self-play. The app's combo pieces (from replays
 * and videos) and its move policy go there too (app-pieces.json, app-policy.json), so the nightly
 * bots play like the app's. Needs a GitHub token that can write to that repository.
 */
object LearningShare {
    private const val REPO = "0megaXP/Yu-Gi-Oh-Engine"
    private const val TAG = "ml-data"
    /** Games per uploaded file, and its largest size before gzip (the counter moves after each file). */
    private const val INPUT_BATCH = 50
    private const val MAX_BYTES = 8_000_000
    private const val K_UPLOADED = "uploaded_inputs_v1"
    private const val K_PIECES_HASH = "app_pieces_hash"
    private const val K_POLICY_HASH = "app_policy_hash"

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
    // the games whose moves were uploaded (followed in the nightly run)
    private val inputsFile get() = File(AppPlatform.current.filesDir, "learning_inputs_sent.json")

    /** Replays skipped because the engine got stuck on them: none now (the phone doesn't re-play them). */
    fun skipped(): List<String> = emptyList()

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
     * Uploads the [files]' games not sent yet: each game's moves (the replay's game messages) and
     * the decks it keeps, with no player names, accounts or settings (the replay's header stays
     * here). The nightly run follows every uploaded game in the rules core with its newest engine
     * (ygoe-cli followall), so each fix to the follower counts for all of them. Returns how many
     * games were sent; nothing happens without a token, when switched off, or while another
     * upload runs.
     */
    suspend fun send(files: List<Pair<String, ByteArray>>, db: CardDatabase, @Suppress("UNUSED_PARAMETER") engine: suspend () -> YgoEngine): Int {
        if (!enabled || token.isBlank() || _state.value.running) return 0
        val sent = load(inputsFile).toMutableSet()
        val todo = files.filter { (_, b) -> OmegaImport.id(b) !in sent }
        if (todo.isEmpty()) {
            runCatching { shareLearned() }
            return 0
        }
        _state.update { it.copy(running = true, note = null, progress = "Getting ready", fraction = 0f) }
        var games = 0
        var failed = 0
        var firstError: String? = null
        val lines = StringBuilder()
        val waiting = ArrayList<String>()
        var inBatch = 0
        suspend fun flush() {
            if (waiting.isEmpty()) return
            if (lines.isNotEmpty()) upload(gzip(lines.toString().toByteArray()), "inputs")
            prefs.edit().putInt(K_UPLOADED, prefs.getInt(K_UPLOADED, 0) + inBatch).apply()
            inBatch = 0
            sent += waiting
            save(sent, inputsFile)
            lines.setLength(0)
            waiting.clear()
            _state.update { it.copy(sent = prefs.getInt(K_UPLOADED, 0)) }
        }
        try {
            todo.forEachIndexed { i, (name, bytes) ->
                _state.update { it.copy(progress = "Preparing game ${i + 1} of ${todo.size}", fraction = i.toFloat() / todo.size) }
                val id = OmegaImport.id(bytes)
                try {
                    val line = withContext(Dispatchers.Default) { input(bytes, db, id) }
                    waiting += id
                    if (line != null) {
                        lines.append(line).append('\n')
                        games++
                        inBatch++
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // an unreadable replay: marked done (it won't read better next time)
                    waiting += id
                    failed++
                    if (firstError == null) firstError = "$name: ${e.message ?: e.javaClass.simpleName}"
                }
                if (lines.length > MAX_BYTES || waiting.size >= INPUT_BATCH) flush()
            }
            flush()
            runCatching { shareLearned() }
            _state.update {
                it.copy(note = listOfNotNull(
                    if (games > 0) "$games game${if (games == 1) "" else "s"} sent to the engine's learning." else "No new games to send.",
                    if (failed > 0) "$failed couldn't be read (first: $firstError)" else null,
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

    /** One game for the engine's follower: its moves and decks (no names), or null if it has no turns. */
    private fun input(bytes: ByteArray, db: CardDatabase, id: String): String? {
        val raw = OmegaReplay.raw(bytes)
        if (raw.messages.size < 2) return null  // ended before the first turn
        val o = JSONObject().put("game", "omega-" + hash(id))
        val msgs = JSONArray()
        for (m in raw.messages) msgs.put(java.util.Base64.getEncoder().encodeToString(m))
        o.put("messages", msgs)
        // the lists the replay keeps, main and Extra Deck apart (its deck codes mix them)
        val decks = JSONArray()
        for (p in 0..1) {
            val cards = raw.decks.getOrNull(p)?.first?.takeIf { it.isNotEmpty() }
            if (cards == null) {
                decks.put(JSONObject.NULL)
                continue
            }
            val (extra, main) = cards.partition { db.find(it)?.isExtraDeck == true }
            decks.put(JSONObject().put("main", JSONArray(main)).put("extra", JSONArray(extra)))
        }
        o.put("decks", decks)
        return o.toString()
    }

    /**
     * The games a deck's learning played on this computer (engine learn.h, Windows): their
     * positions and policy examples not sent yet, uploaded as local-pos-* / local-pol-*.jsonl.gz
     * to the ml-data release, where the nightly run learns from them as from its own self-play.
     * Numbers and the engine's feature ids only: no deck names, players or accounts. Returns how
     * many games were sent; nothing happens without a token or when sharing is switched off.
     */
    suspend fun sendLocal(dir: File, skipLatest: Boolean = false): Int = withContext(Dispatchers.IO) {
        if (!enabled || token.isBlank()) return@withContext 0
        val sentFile = File(dir, "uploaded_games.json")
        val sent = load(sentFile).toMutableSet()
        fun read(name: String, key: String): List<Pair<String, JSONObject>> {
            val f = File(dir, name)
            if (!f.isFile) return emptyList()
            return f.readLines().mapNotNull { l ->
                runCatching { JSONObject(l) }.getOrNull()?.let { o -> o.optString(key).takeIf { it.isNotEmpty() }?.let { it to o } }
            }
        }
        val pos = read("positions.jsonl", "game")
        val pol = read("policy_examples.jsonl", "g")
        // while a session is writing, the game it wrote last may still be coming in: it goes next time
        val games = pos.map { it.first }.distinct().let { if (skipLatest) it.dropLast(1) else it }
        val fresh = games.toSet() - sent
        if (fresh.isEmpty()) return@withContext 0
        // game ids marked as the app's local games (apart from the nightly runs' own)
        suspend fun send(lines: List<Pair<String, JSONObject>>, key: String, kind: String) {
            val out = StringBuilder()
            for ((g, o) in lines) {
                if (g !in fresh) continue
                o.put(key, "local-$g")
                if (kind == "local-pos") o.put("src", "local")
                out.append(o.toString()).append('\n')
                if (out.length > MAX_BYTES) {
                    upload(gzip(out.toString().toByteArray()), kind)
                    out.setLength(0)
                }
            }
            if (out.isNotEmpty()) upload(gzip(out.toString().toByteArray()), kind)
        }
        send(pos, "game", "local-pos")
        send(pol, "g", "local-pol")
        sent += fresh
        save(sent, sentFile)
        fresh.size
    }

    /**
     * The combo lines "Deepen combo lines" found on this computer ([dir]/newpieces/*.json, one
     * file per batch of opening hands): uploaded as local-newpieces-*.json to ml-data, where the
     * nightly run merges them into the format's shared pool of pieces; each file is removed here
     * once sent. Returns how many files were sent.
     */
    suspend fun sendLines(dir: File): Int = withContext(Dispatchers.IO) {
        if (!enabled || token.isBlank()) return@withContext 0
        val files = File(dir, "newpieces").listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name } ?: return@withContext 0
        var n = 0
        for (f in files) {
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            if (runCatching { JSONObject(text) }.isFailure) continue  // still being written
            val release = releaseId()
            val name = "local-newpieces-${System.currentTimeMillis()}-${hash(f.name + text.length).take(6)}.json"
            val (code, body) = request("POST", "https://uploads.github.com/repos/$REPO/releases/$release/assets?name=$name",
                text.toByteArray(), "application/json")
            if (code !in 200..299) throw IllegalStateException(problem(code, body))
            f.delete()
            n++
        }
        n
    }

    /**
     * The format's shared pool of combo pieces (combo-pool.json in ml-data, public), its "pieces"
     * array as JSON: the nightly bots play their lines from it, and so do the computer's when they
     * play the format's games. Fetched at most every 6 hours (kept in [cache]); null when it can't be read.
     */
    suspend fun comboPool(cache: File): String? = withContext(Dispatchers.IO) {
        val fresh = cache.isFile && System.currentTimeMillis() - cache.lastModified() < 6 * 3600_000L
        if (!fresh) runCatching {
            val conn = URL("https://github.com/$REPO/releases/download/$TAG/combo-pool.json").openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("User-Agent", "YGOSandbox")
            try {
                if (conn.responseCode in 200..299) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    cache.parentFile?.mkdirs()
                    val tmp = File(cache.parentFile, cache.name + ".tmp")
                    tmp.writeText(text)
                    com.ygosandbox.app.platform.replaceFile(tmp, cache)
                }
            } finally {
                conn.disconnect()
            }
        }
        runCatching { JSONObject(cache.readText()).optJSONArray("pieces")?.toString() }.getOrNull()
    }

    /** How the uploads went, for Settings. */
    fun followSummary(): String? {
        val n = prefs.getInt(K_UPLOADED, 0)
        if (n == 0) return null
        return "$n game${if (n == 1) "" else "s"} sent to the engine's learning. The engine re-plays every one by the rules " +
            "in its nightly run, with its newest version."
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

    private suspend fun upload(gz: ByteArray, kind: String = "replays") = withContext(Dispatchers.IO) {
        val release = releaseId()
        val name = "$kind-${System.currentTimeMillis()}-${hash(java.util.UUID.randomUUID().toString()).take(6)}.jsonl.gz"
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
