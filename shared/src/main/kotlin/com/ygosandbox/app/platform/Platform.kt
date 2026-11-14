package com.ygosandbox.app.platform

import com.ygoengine.YgoEngine
import com.ygosandbox.app.combos.CardNames
import com.ygosandbox.app.combos.Frame
import com.ygosandbox.app.combos.FreeReader
import java.io.File

/**
 * What the shared app (screens, view models, stores) needs from the system it runs on.
 * The Android app and the Windows app each set [AppPlatform.current] before anything else.
 */
interface Platform {
    /** "Android" or "Windows", for the few messages that differ. */
    val name: String

    /** The app's version, e.g. "0.0.42". */
    val versionName: String

    /** Private storage for the app's files (decks, card database, settings). */
    val filesDir: File

    /** Storage the system may clear (downloads in progress, update installers). */
    val cacheDir: File

    /** A small named key-value store (Android's SharedPreferences, or a file on the desktop). */
    fun preferences(name: String): KeyValueStore

    /** Loads the play engine (on Android the first run copies its card scripts into storage). */
    suspend fun openEngine(): YgoEngine

    // ---- crash reports (CrashReports)

    /** Crash files the system writes when the app dies (Java's hs_err_pid*.log on a computer). */
    fun crashFiles(): List<File> = emptyList()

    /** Why the app's last run ended, when the system keeps that and it was a crash (Android), else null. */
    fun lastExitReason(since: Long): String? = null

    // ---- updates (GitHub releases)

    /** File name ending of this platform's download in a release (".apk", ".msi"). */
    val updateAssetSuffix: String

    /** Whether the app may install updates now (Android asks once); true elsewhere. */
    fun canInstallUpdates(): Boolean = true

    /** Opens the system screen that allows installing updates (Android only). */
    fun requestInstallPermission() {}

    /** Hands the downloaded update to the system installer. */
    fun installUpdate(file: File)

    // ---- background work

    /** A long job started; [first] when no other job was running (Android starts its service). */
    fun onWorkStarted(first: Boolean) {}

    /** Shown when a job was cut short because the app was closed by the system. */
    val interruptedHint: String
        get() = "Open the app again and run it once more: it continues where it stopped."

    // ---- files

    /**
     * Lets the person pick files (name to contents), starting in [startIn] where the system allows.
     * Empty when they cancel.
     */
    suspend fun pickFiles(title: String, startIn: java.io.File? = null): List<Pair<String, ByteArray>> = emptyList()

    /**
     * Lets the person choose a folder, returned as text the app keeps: a path on a computer, a
     * folder the phone granted access to on Android. Null when they cancel.
     */
    suspend fun pickFolder(title: String, startIn: String? = null): String? = null

    /** Whether a folder can also be typed or pasted as a path (computers; phones only grant folders by picking). */
    val canTypeFolder: Boolean get() = false

    /**
     * The files in [folder] (from [pickFolder], or a typed path) and its subfolders, up to three
     * levels down, whose name ends with [extension] and that [accept] (name, last changed in ms)
     * lets through: name to contents.
     */
    suspend fun readFolder(folder: String, extension: String, accept: (String, Long) -> Boolean): List<Pair<String, ByteArray>> = emptyList()

    /** YGO Omega's replay folder on this computer, or null (not installed, or not a computer). */
    val omegaReplays: java.io.File? get() = null

    // ---- video reading (Combos and Replays tabs)

    /** Cutting videos into frames and reading text on them, or null where it isn't available yet. */
    val video: VideoPlatform?
}

/** The running app's [Platform]. Set once at start-up. */
object AppPlatform {
    @Volatile private var platform: Platform? = null

    var current: Platform
        get() = platform ?: error("AppPlatform.current isn't set yet")
        set(value) { platform = value }
}

/** Subset of Android's SharedPreferences that the app uses. */
interface KeyValueStore {
    fun getString(key: String, default: String?): String?
    fun getLong(key: String, default: Long): Long
    fun getInt(key: String, default: Int): Int
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getFloat(key: String, default: Float): Float
    fun contains(key: String): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putLong(key: String, value: Long): Editor
        fun putInt(key: String, value: Int): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putFloat(key: String, value: Float): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        /** Saves in the background. */
        fun apply()
        /** Saves now; returns false if it couldn't. */
        fun commit(): Boolean
    }
}

/** Video tools the Combos and Replays readers use. */
interface VideoPlatform {
    suspend fun extractFrames(
        video: File,
        dir: File,
        maxFrames: Int,
        stepMs: Long,
        onPosition: (Long, Long) -> Unit,
        onProgress: (Float) -> Unit,
    ): List<Frame>

    suspend fun readFrames(
        frames: List<Frame>,
        cn: CardNames,
        allowed: Set<Long>,
        handTraps: Set<Long>,
        title: String,
        description: String,
        extraDeck: Set<Long>,
        captions: String,
        onProgress: (Float) -> Unit,
    ): FreeReader.Result
}

/** Thrown where reading videos isn't available on this platform yet. */
class VideoUnavailableException(platform: String) :
    Exception("Reading videos isn't available on $platform yet. Use the phone app for the Combos and Replays readers.")

/** The platform's video tools, or a clear error where there are none. */
fun platformVideo(): VideoPlatform =
    AppPlatform.current.video ?: throw VideoUnavailableException(AppPlatform.current.name)
