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

    /**
     * %LOCALAPPDATA%\YGO Sandbox Cache on Windows: outside %LOCALAPPDATA%\YGO Sandbox, which is the
     * app's install folder, which an update removes along with the old version.
     */
    override val cacheDir: File = (System.getenv("LOCALAPPDATA")?.let { File(it, "YGO Sandbox Cache") }
        ?: File(filesDir, "cache")).apply { mkdirs() }

    private val stores = HashMap<String, PropertiesStore>()

    @Synchronized
    override fun preferences(name: String): KeyValueStore =
        stores.getOrPut(name) { PropertiesStore(File(filesDir, "settings/$name.properties")) }

    /**
     * Java's crash files (hs_err_pid*.log): the app asks for them next to its install folder
     * (-XX:ErrorFile); Java falls back to the working folder or the temp folder.
     */
    override fun crashFiles(): List<File> {
        val dirs = listOfNotNull(
            resources?.parentFile?.parentFile, resources?.parentFile,
            File(System.getProperty("user.dir")), File(System.getProperty("java.io.tmpdir")), File(System.getProperty("user.home")),
        ).distinct()
        return dirs.flatMap { d -> d.listFiles { f -> f.name.startsWith("hs_err_pid") && f.name.endsWith(".log") }?.toList().orEmpty() }
            .sortedByDescending { it.lastModified() }
    }

    override suspend fun openEngine(): YgoEngine = withContext(Dispatchers.IO) {
        val pack = resources?.let { File(it, "ygoe") }?.takeIf { File(it, "scripts.pack").exists() }
            ?: error("The engine's card pack is missing from the app's folder. Reinstall YGO Sandbox.")
        YgoEngine.open(pack)
    }

    override val updateAssetSuffix = ".msi"

    /**
     * Installs the update once this app has fully closed, then opens the new version.
     *
     * The installer has to wait: started while the app still runs, it removes the old version
     * but can't replace the files the app holds open, which left no app at all. It runs from a
     * copy in %TEMP% (outside the install folder, which the update replaces) and from %TEMP% as
     * working folder. If the app still isn't there afterwards it tries once more, and failing
     * that opens the normal installer window so it can be finished by hand. The installer log is
     * %TEMP%\YGOSandbox-update.log; each attempt's result is added to YGOSandbox-update.txt there.
     */
    override fun installUpdate(file: File) {
        val tmp = File(System.getProperty("java.io.tmpdir"))
        val msi = File(tmp, "YGOSandbox-update.msi")
        file.copyTo(msi, overwrite = true)
        val log = File(tmp, "YGOSandbox-update.log").apply { delete() }
        val status = File(tmp, "YGOSandbox-update.txt")
        val pid = ProcessHandle.current().pid()
        fun q(x: String) = "'" + x.replace("'", "''") + "'"
        fun quoted(f: File) = q("\"" + f.absolutePath + "\"")
        val script = File(tmp, "YGOSandbox-update.ps1")
        script.writeText(
            """
            Wait-Process -Id $pid -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 2
            ${'$'}exe = ${q(appExe()?.absolutePath.orEmpty())}
            function Install { (Start-Process msiexec -ArgumentList @('/i', ${quoted(msi)}, '/passive', '/norestart', '/l*v+', ${quoted(log)}) -Wait -PassThru).ExitCode }
            ${'$'}code = Install
            if (${'$'}exe -and -not (Test-Path ${'$'}exe)) { Start-Sleep -Seconds 3; ${'$'}code = Install }
            "${'$'}(Get-Date -Format s) installer exit code ${'$'}code" | Out-File -Append -Encoding utf8 ${q(status.absolutePath)}
            if (${'$'}exe -and (Test-Path ${'$'}exe)) { Start-Process -FilePath ${'$'}exe -WorkingDirectory (Split-Path ${'$'}exe) }
            elseif (${'$'}exe) { Start-Process msiexec -ArgumentList @('/i', ${quoted(msi)}) }
            """.trimIndent()
        )
        ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.absolutePath)
            .directory(tmp)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        Thread {
            Thread.sleep(500)
            com.ygosandbox.app.platform.CrashReports.cleanExit()
            exitProcess(0)
        }.start()
    }

    /**
     * The installed app's launcher ("YGO Sandbox.exe"), to open again after an update. Null when
     * running from a development build (java.exe), where there is nothing to reopen.
     */
    private fun appExe(): File? {
        val self = ProcessHandle.current().info().command().orElse(null)?.let(::File)
        if (self != null && self.name.endsWith(".exe", ignoreCase = true) && !self.name.startsWith("java", ignoreCase = true))
            return self
        return System.getenv("LOCALAPPDATA")?.let { File(it, "YGO Sandbox/YGO Sandbox.exe") }?.takeIf { it.exists() }
    }

    override val interruptedHint = "Open the app again and run it once more: it continues where it stopped."

    /** YGO Omega keeps its replays under %USERPROFILE%\AppData\LocalLow\Duelists Unite\YGO Omega. */
    override val omegaReplays: File?
        get() {
            val home = System.getenv("USERPROFILE") ?: System.getProperty("user.home") ?: return null
            val root = File(home, "AppData/LocalLow/Duelists Unite/YGO Omega")
            if (!root.isDirectory) return null
            return root.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("Replays", ignoreCase = true) } ?: root
        }

    override val canTypeFolder = true

    override suspend fun pickFolder(title: String, startIn: String?): String? = withContext(Dispatchers.IO) {
        val start = startIn?.let(::File)?.takeIf { it.isDirectory } ?: omegaReplays
        val chooser = javax.swing.JFileChooser(start).apply {
            dialogTitle = title
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        }
        var picked: String? = null
        javax.swing.SwingUtilities.invokeAndWait {
            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) picked = chooser.selectedFile?.absolutePath
        }
        picked
    }

    override suspend fun readFolder(folder: String, extension: String, accept: (String, Long) -> Boolean): List<Pair<String, ByteArray>> =
        withContext(Dispatchers.IO) {
            val dir = File(folder.trim().trim('"'))
            if (!dir.isDirectory) error("Folder not found: ${dir.path}")
            dir.walkTopDown().maxDepth(4)
                .filter { it.isFile && it.name.endsWith(extension, ignoreCase = true) && accept(it.name, it.lastModified()) }
                .map { it.name to it.readBytes() }.toList()
        }

    override suspend fun pickFiles(title: String, startIn: File?): List<Pair<String, ByteArray>> = withContext(Dispatchers.IO) {
        val chooser = javax.swing.JFileChooser(startIn ?: omegaReplays).apply {
            dialogTitle = title
            isMultiSelectionEnabled = true
            fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("YGO Omega replays (*.bytes)", "bytes")
        }
        var picked: Array<File> = emptyArray()
        javax.swing.SwingUtilities.invokeAndWait {
            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION)
                picked = chooser.selectedFiles.takeIf { it.isNotEmpty() } ?: arrayOf(chooser.selectedFile)
        }
        picked.filter { it.isFile }.map { it.name to it.readBytes() }
    }

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
