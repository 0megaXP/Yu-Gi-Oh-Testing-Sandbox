package com.ygosandbox.desktop

import com.ygoengine.YgoEngine
import com.ygosandbox.app.platform.KeyValueStore
import com.ygosandbox.app.platform.Platform
import com.ygosandbox.app.platform.VideoPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import kotlin.system.exitProcess

/** The Windows app's [Platform]. [resources] is the app's bundled resources folder (engine DLL and card pack). */
class DesktopPlatform(private val resources: File?) : Platform {
    override val name: String =
        System.getProperty("os.name").orEmpty().let { if (it.startsWith("Windows")) "Windows" else it.ifEmpty { "the desktop" } }

    override val versionName: String = DesktopVersion.NAME

    /** %APPDATA%\YGO Sandbox on Windows (roams with the user), ~/.ygosandbox elsewhere. */
    override val filesDir: File = (System.getenv("APPDATA")?.let { File(it, "YGO Sandbox") }
        ?: File(System.getProperty("user.home"), ".ygosandbox")).apply { mkdirs() }

    /** %LOCALAPPDATA%\YGO Sandbox\cache on Windows. */
    override val cacheDir: File = (System.getenv("LOCALAPPDATA")?.let { File(it, "YGO Sandbox/cache") }
        ?: File(filesDir, "cache")).apply { mkdirs() }

    private val stores = HashMap<String, PropertiesStore>()

    @Synchronized
    override fun preferences(name: String): KeyValueStore =
        stores.getOrPut(name) { PropertiesStore(File(filesDir, "settings/$name.properties")) }

    override suspend fun openEngine(): YgoEngine = withContext(Dispatchers.IO) {
        val pack = resources?.let { File(it, "ygoe") }?.takeIf { File(it, "scripts.pack").exists() }
            ?: error("The engine's card pack is missing from the app's folder. Reinstall YGO Sandbox.")
        YgoEngine.open(pack)
    }

    override val updateAssetSuffix = ".msi"

    /** Runs the new installer (it upgrades this copy in place) and closes the app so files can be replaced. */
    override fun installUpdate(file: File) {
        ProcessBuilder("msiexec", "/i", file.absolutePath, "/passive", "/norestart").start()
        Thread {
            Thread.sleep(1500)
            exitProcess(0)
        }.start()
    }

    override val interruptedHint = "Open the app again and run it once more: it continues where it stopped."

    /** Frame decoding and text recognition aren't on the desktop yet (the phone app has them). */
    override val video: VideoPlatform? = null
}

/** [KeyValueStore] kept in a .properties file. */
private class PropertiesStore(private val file: File) : KeyValueStore {
    private val props = Properties().apply {
        if (file.exists()) runCatching { file.inputStream().use { load(it) } }
    }

    @Synchronized private fun get(key: String): String? = props.getProperty(key)

    override fun getString(key: String, default: String?) = get(key) ?: default
    override fun getLong(key: String, default: Long) = get(key)?.toLongOrNull() ?: default
    override fun getInt(key: String, default: Int) = get(key)?.toIntOrNull() ?: default
    override fun getBoolean(key: String, default: Boolean) = get(key)?.toBooleanStrictOrNull() ?: default
    override fun getFloat(key: String, default: Float) = get(key)?.toFloatOrNull() ?: default
    override fun contains(key: String) = get(key) != null
    override fun edit(): KeyValueStore.Editor = Editor()

    @Synchronized private fun save(changes: Map<String, String?>, clear: Boolean): Boolean {
        if (clear) props.clear()
        changes.forEach { (k, v) -> if (v == null) props.remove(k) else props.setProperty(k, v) }
        return runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { props.store(it, null) }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.isSuccess
    }

    private inner class Editor : KeyValueStore.Editor {
        private val changes = LinkedHashMap<String, String?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value.toString() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value.toString() }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value.toString() }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value.toString() }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clear = true }
        override fun apply() { save(changes, clear) }
        override fun commit() = save(changes, clear)
    }
}
