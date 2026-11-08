package com.ygosandbox.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.ygosandbox.app.BuildConfig
import com.ygosandbox.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val version: String, val apkUrl: String, val notes: String)


/**
 * Checks the latest GitHub release of the YGO Sandbox repository and installs it.
 * Releases are published by the GitHub build on every push to main.
 */
object Updater {
    private const val REPO = "0megaXP/Yu-Gi-Oh-Testing-Sandbox"

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Returns the newer release, or null if this is already the latest. */
    suspend fun check(token: String): UpdateInfo? {
        val headers = mutableMapOf("Accept" to "application/vnd.github+json")
        if (token.isNotEmpty()) headers["Authorization"] = "Bearer $token"
        val json = JSONObject(Http.get("https://api.github.com/repos/$REPO/releases/latest", headers))
        val version = json.optString("tag_name").removePrefix("v")
        val assets = json.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
        if (!isNewer(version, currentVersion)) return null
        // The API asset URL works for private repositories (with the token); browser_download_url doesn't.
        return UpdateInfo(version, apk.getString("url"), json.optString("body"))
    }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Downloads the APK into the app's cache, reporting progress from 0 to 1. */
    suspend fun download(context: Context, info: UpdateInfo, token: String, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "YGOSandbox-${info.version}.apk")
            // GitHub answers with a redirect to a signed download link. Follow it by hand so
            // the token is only ever sent to api.github.com.
            val api = URL(info.apkUrl).openConnection() as HttpURLConnection
            val location = try {
                api.instanceFollowRedirects = false
                api.connectTimeout = 15_000
                api.setRequestProperty("Accept", "application/octet-stream")
                if (token.isNotEmpty()) api.setRequestProperty("Authorization", "Bearer $token")
                val code = api.responseCode
                if (code !in 300..399) throw Exception("Download failed (HTTP $code)")
                api.getHeaderField("Location") ?: throw Exception("Download link missing")
            } finally {
                api.disconnect()
            }
            val conn = URL(location).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                if (conn.responseCode !in 200..299) throw Exception("Download failed (HTTP ${conn.responseCode})")
                val total = conn.contentLengthLong
                var done = 0L
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            file
        }

    /** Android asks once whether YGO Sandbox may install apps. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Hands the APK to Android's installer, which shows the usual "Update" screen. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
