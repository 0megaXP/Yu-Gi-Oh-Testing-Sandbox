package com.ygosandbox.app.platform

import android.content.Context
import android.content.SharedPreferences
import com.ygoengine.YgoEngine
import com.ygoengine.open
import com.ygosandbox.app.BuildConfig
import com.ygosandbox.app.combos.AndroidFrames
import com.ygosandbox.app.combos.AndroidFreeReader
import com.ygosandbox.app.combos.CardNames
import com.ygosandbox.app.combos.Frame
import com.ygosandbox.app.combos.FreeReader
import com.ygosandbox.app.update.AndroidUpdater
import com.ygosandbox.app.work.AndroidWork
import java.io.File

/** The Android phone app's [Platform]. */
class AndroidPlatform(context: Context) : Platform {
    private val app: Context = context.applicationContext

    override val name = "Android"
    override val versionName: String = BuildConfig.VERSION_NAME
    override val filesDir: File get() = app.filesDir
    override val cacheDir: File get() = app.cacheDir

    override fun preferences(name: String): KeyValueStore =
        SharedPrefsStore(app.getSharedPreferences(name, Context.MODE_PRIVATE))

    override suspend fun openEngine(): YgoEngine {
        // a newer engine downloaded from the engine's releases, else the one the app came with
        com.ygosandbox.app.update.EngineUpdate.active?.dir?.takeIf { File(it, "scripts.pack").exists() }?.let { dir ->
            runCatching { return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { YgoEngine.open(dir) } }
        }
        return YgoEngine.open(app)
    }

    override val engineLibAsset: String? =
        android.os.Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }?.let { "libygoe_jni-$it.so" }

    override fun lastExitReason(since: Long): String? {
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        val am = app.getSystemService(android.app.ActivityManager::class.java) ?: return null
        val info = runCatching { am.getHistoricalProcessExitReasons(app.packageName, 0, 1).firstOrNull() }.getOrNull() ?: return null
        if (info.timestamp < since) return null
        val detail = info.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        return when (info.reason) {
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ->
                "The app closed because native code crashed, most likely the play engine (the part that runs the duel rules and the bots)$detail."
            android.app.ApplicationExitInfo.REASON_CRASH -> "The app closed because of an error$detail."
            android.app.ApplicationExitInfo.REASON_ANR -> "Android closed the app because it stopped responding$detail."
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Android closed the app because the phone ran low on memory."
            else -> null
        }
    }

    override val updateAssetSuffix = ".apk"
    override fun canInstallUpdates() = AndroidUpdater.canInstall(app)
    override fun requestInstallPermission() = AndroidUpdater.openInstallPermissionSettings(app)
    override fun installUpdate(file: File) = AndroidUpdater.install(app, file)

    override suspend fun shareFile(file: File, mime: String, title: String): String? {
        // the file goes through the app's FileProvider (cache/shared/), so other apps can read it
        val dir = File(app.cacheDir, "shared").apply { mkdirs() }
        val copy = if (file.parentFile == dir) file else File(dir, file.name).also { file.copyTo(it, overwrite = true) }
        val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.updates", copy)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .putExtra(android.content.Intent.EXTRA_SUBJECT, title)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri(title, uri)
        app.startActivity(
            android.content.Intent.createChooser(send, title)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        return "Choose where to send ${copy.name}"
    }

    override fun onWorkStarted(first: Boolean) = AndroidWork.onWorkStarted(first)

    override suspend fun pickFiles(title: String, startIn: File?, extensions: List<String>): List<Pair<String, ByteArray>> = AndroidFilePicker.pick()

    override suspend fun pickFolder(title: String, startIn: String?): String? = AndroidFilePicker.pickFolder()

    override suspend fun readFolder(folder: String, extension: String, accept: (String, Long) -> Boolean): List<Pair<String, ByteArray>> =
        AndroidFilePicker.readFolder(folder, extension, accept)

    override val interruptedHint =
        "Allow YGO Sandbox to run without battery restrictions (Android settings → Apps → YGO Sandbox → Battery) " +
            "and read it again: it continues where it stopped."

    override val video: VideoPlatform = object : VideoPlatform {
        override suspend fun extractFrames(
            video: File, dir: File, maxFrames: Int, stepMs: Long,
            onPosition: (Long, Long) -> Unit, onProgress: (Float) -> Unit,
        ): List<Frame> = AndroidFrames.extract(video, dir, maxFrames, stepMs, onPosition, onProgress)

        override suspend fun readFrames(
            frames: List<Frame>, cn: CardNames, allowed: Set<Long>, handTraps: Set<Long>, title: String,
            description: String, extraDeck: Set<Long>, captions: String, onProgress: (Float) -> Unit,
        ): FreeReader.Result = AndroidFreeReader.read(frames, cn, allowed, handTraps, title, description, extraDeck, captions, onProgress)
    }
}

/** [KeyValueStore] over SharedPreferences, so settings saved by earlier versions stay. */
private class SharedPrefsStore(private val p: SharedPreferences) : KeyValueStore {
    override fun getString(key: String, default: String?) = p.getString(key, default)
    override fun getLong(key: String, default: Long) = p.getLong(key, default)
    override fun getInt(key: String, default: Int) = p.getInt(key, default)
    override fun getBoolean(key: String, default: Boolean) = p.getBoolean(key, default)
    override fun getFloat(key: String, default: Float) = p.getFloat(key, default)
    override fun contains(key: String) = p.contains(key)
    override fun edit(): KeyValueStore.Editor = Editor(p.edit())

    private class Editor(private val e: SharedPreferences.Editor) : KeyValueStore.Editor {
        override fun putString(key: String, value: String?) = apply { e.putString(key, value) }
        override fun putLong(key: String, value: Long) = apply { e.putLong(key, value) }
        override fun putInt(key: String, value: Int) = apply { e.putInt(key, value) }
        override fun putBoolean(key: String, value: Boolean) = apply { e.putBoolean(key, value) }
        override fun putFloat(key: String, value: Float) = apply { e.putFloat(key, value) }
        override fun remove(key: String) = apply { e.remove(key) }
        override fun clear() = apply { e.clear() }
        override fun apply() = e.apply()
        override fun commit() = e.commit()
    }
}
