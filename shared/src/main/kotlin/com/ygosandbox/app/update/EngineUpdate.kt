package com.ygosandbox.app.update

import com.ygoengine.YgoEngine
import com.ygosandbox.app.platform.AppPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Newer engines without updating the app: the engine repository publishes its latest build
 * (the native library for Windows and Android, and the card pack with the trained value
 * network) as the "engine-latest" release. The app downloads it in the background, checks every
 * file, and uses it from its next start. It only takes builds made for its own engine API
 * ([YgoEngine.API_COMPAT]); a downloaded engine that fails to load is set aside and the
 * engine the app came with is used.
 */
object EngineUpdate {
    private const val BASE = "https://github.com/0megaXP/Yu-Gi-Oh-Engine/releases/download/engine-latest/"

    data class Installed(val engine: String, val committed: String, val dir: File, val lib: File)

    data class State(
        val checking: Boolean = false,
        val note: String? = null,
        /** A downloaded engine waiting for the next start (its commit). */
        val ready: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state

    /** The downloaded engine this run uses, or null for the app's own. */
    var active: Installed? = null
        private set

    private val root get() = File(AppPlatform.current.filesDir, "engine-updates")
    private val prefs get() = AppPlatform.current.preferences("engine_update")

    var automatic: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) = prefs.edit().putBoolean("auto", v).apply()

    // ---------------------------------------------------------------- at start

    /**
     * Picks the engine for this run, before the engine library is first loaded: the downloaded
     * one when there is one for this app's engine API that hasn't failed to load. [libAsset] is
     * this platform's library in the release (null: none, the app's own engine is used).
     */
    fun select(libAsset: String?): Installed? {
        active = null
        if (libAsset == null) return null
        return runCatching {
            val cur = File(root, "current.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) } ?: return null
            val engine = cur.getString("engine")
            val dir = File(root, engine)
            val bad = badList()
            if (cur.optInt("compat") != YgoEngine.API_COMPAT || engine in bad || !File(dir, "ready").isFile) return null
            // the last run tried this one and didn't get it open: it doesn't work here
            val trying = File(root, "trying")
            if (trying.isFile && trying.readText().trim() == engine) {
                trying.delete()
                File(root, "bad.txt").appendText("$engine\n")
                return null
            }
            val lib = File(dir, libAsset).takeIf { it.isFile } ?: return null
            trying.writeText(engine)
            Installed(engine, cur.optString("committed"), dir, lib).also { active = it }
        }.getOrNull()
    }

    /** The engine opened: the one picked works (see [select]). Old downloads are cleared away. */
    fun opened() {
        runCatching { File(root, "trying").delete() }
        runCatching {
            val keep = setOfNotNull(active?.engine, current()?.optString("engine"))
            root.listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach { it.deleteRecursively() }
        }
    }

    /** The picked engine's library didn't load: set aside; the app's own is used from the next start. */
    fun failedToLoad() {
        val a = active ?: return
        runCatching {
            File(root, "trying").delete()
            File(root, "bad.txt").appendText("${a.engine}\n")
        }
        _state.update { it.copy(note = "The downloaded engine didn't load on this device. Restart the app to use its own engine.") }
    }

    private fun badList(): Set<String> =
        runCatching { File(root, "bad.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }.getOrDefault(emptySet())

    private fun current(): JSONObject? = runCatching { JSONObject(File(root, "current.json").readText()) }.getOrNull()

    /** Which engine runs, for Settings. */
    fun describe(): String {
        val loaded = YgoEngine.nativeVersion()?.first
        val a = active
        return when {
            a != null -> "Downloaded engine ${a.engine.take(7)}" + (a.committed.take(10).takeIf { it.isNotBlank() }?.let { " from $it" } ?: "")
            loaded != null && loaded != "unknown" -> "The app's own engine ${loaded.take(7)}"
            else -> "The app's own engine"
        }
    }

    // ---------------------------------------------------------------- checking

    /**
     * Downloads the latest engine when it's newer than the one running (and not downloaded yet).
     * Call once the engine has been opened, so the running one is known.
     */
    suspend fun check(libAsset: String?, manual: Boolean = false) = withContext(Dispatchers.IO) {
        if (libAsset == null) {
            if (manual) _state.update { it.copy(note = "Engine updates aren't available on this computer.") }
            return@withContext
        }
        if (_state.value.checking) return@withContext
        _state.update { it.copy(checking = true, note = if (manual) "Looking for a newer engine…" else it.note) }
        try {
            val m = JSONObject(String(get(BASE + "manifest.json")))
            val engine = m.getString("engine")
            val running = active?.engine ?: YgoEngine.nativeVersion()?.first
            val compat = m.optInt("compat")
            when {
                compat != YgoEngine.API_COMPAT -> _state.update {
                    it.copy(note = if (manual) "The newest engine needs a newer app: update YGO Sandbox to get it." else null)
                }
                engine == running -> _state.update { it.copy(note = if (manual) "The engine is up to date." else null, ready = null) }
                engine in badList() -> _state.update { it.copy(note = if (manual) "The newest engine didn't load on this device; waiting for the next one." else null) }
                current()?.optString("engine") == engine && File(File(root, engine), "ready").isFile ->
                    _state.update { it.copy(ready = engine, note = "A newer engine is downloaded: it's used from the next start.") }
                else -> {
                    download(m, libAsset)
                    _state.update { it.copy(ready = engine, note = "A newer engine (${engine.take(7)}) is downloaded: it's used from the next start.") }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (manual) _state.update { it.copy(note = "Couldn't check for a newer engine: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            _state.update { it.copy(checking = false) }
        }
    }

    private fun download(m: JSONObject, libAsset: String) {
        val engine = m.getString("engine")
        val files = m.getJSONObject("files")
        // the library for this device and the whole card pack
        val wanted = files.keys().asSequence().filter { n ->
            n == libAsset || !(n.endsWith(".dll") || n.endsWith(".so") || n.endsWith(".debug"))
        }.toList()
        if (libAsset !in wanted) throw IllegalStateException("no engine library for this device in the release")
        root.mkdirs()
        val tmp = File(root, "$engine.part").apply { deleteRecursively(); mkdirs() }
        for ((i, name) in wanted.withIndex()) {
            _state.update { it.copy(note = "Downloading the engine: ${i + 1} of ${wanted.size} ($name)") }
            val info = files.getJSONObject(name)
            val bytes = get(BASE + name)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (bytes.size.toLong() != info.optLong("size") || sha != info.optString("sha256"))
                throw IllegalStateException("$name didn't arrive whole (the release may be updating; tried again later)")
            File(tmp, name).writeBytes(bytes)
        }
        File(tmp, "ready").writeText(engine)
        val dir = File(root, engine)
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) throw IllegalStateException("couldn't store the engine")
        val cur = JSONObject().put("engine", engine).put("committed", m.optString("committed"))
            .put("compat", m.optInt("compat")).put("pack", m.optString("pack"))
        val curTmp = File(root, "current.json.tmp")
        curTmp.writeText(cur.toString())
        com.ygosandbox.app.platform.replaceFile(curTmp, File(root, "current.json"))
    }

    private fun get(url: String): ByteArray {
        var u = url
        repeat(5) {
            val conn = URL(u).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("User-Agent", "YGOSandbox")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    u = conn.getHeaderField("Location") ?: throw IllegalStateException("redirect without a location")
                    return@repeat
                }
                if (code == 404) throw IllegalStateException("no engine published yet")
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                return conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("too many redirects")
    }
}
