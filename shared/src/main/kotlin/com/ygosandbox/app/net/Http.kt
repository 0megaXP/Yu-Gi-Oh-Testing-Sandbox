package com.ygosandbox.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiException(val code: Int, message: String) : Exception(message)

/** Minimal HTTP helper built on HttpURLConnection, so the app needs no extra libraries. */
object Http {
    private const val USER_AGENT = "YGOSandbox/1.0 (Android)"

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val conn = open(url, headers)
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ApiException(code, "HTTP $code: ${text.take(300)}")
            text
        } finally {
            conn.disconnect()
        }
    }

    /** Streams a (large) response straight into [target], reporting progress 0..1 when the size is known. */
    suspend fun download(url: String, target: File, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        val conn = open(url, emptyMap())
        conn.readTimeout = 120_000
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw ApiException(code, "HTTP $code")
            val total = conn.contentLengthLong
            var done = 0L
            target.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
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
    }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn
    }
}
