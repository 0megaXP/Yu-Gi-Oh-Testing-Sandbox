package com.ygosandbox.app.combos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google's Gemini API (generativelanguage.googleapis.com), which has a free tier: an API key from
 * Google AI Studio, with per-minute and per-day request limits instead of a bill. Answers come back
 * as JSON that follows a schema.
 */
class GeminiClient(private val apiKey: String, private val model: String) {

    /**
     * One generateContent call answering in JSON by [schema]. [parts] are Gemini content parts
     * ([text], [youtube]). Waits out the free tier's per-minute limit; a used-up daily limit fails.
     */
    suspend fun json(system: String, parts: JSONArray, schema: JSONObject, timeoutMs: Int = 300_000, lowMedia: Boolean = false): JSONObject {
        // Google has renamed the structured-output fields over time: the newest first, older ones when refused
        val configs = listOf(
            JSONObject().put("responseFormat", JSONObject().put("text",
                JSONObject().put("mimeType", "application/json").put("schema", schema))),
            JSONObject().put("responseMimeType", "application/json").put("responseJsonSchema", schema),
            JSONObject().put("responseMimeType", "application/json"),
        )
        var lastError = ""
        for ((ci, config) in configs.withIndex()) {
            if (lowMedia) config.put("mediaResolution", "MEDIA_RESOLUTION_LOW")
            val sys = if (ci == configs.lastIndex) "$system\n\nAnswer with one JSON object following this JSON Schema:\n$schema" else system
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(text(sys))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", config)
                .toString().toByteArray()
            var wait = 15_000L
            var timeouts = 0
            var refused = false
            for (attempt in 0 until 6) {
                val (code, reply) = withContext(Dispatchers.IO) { post(body, timeoutMs) }
                when {
                    code in 200..299 -> {
                        val answer = answerText(JSONObject(reply))
                        if (answer != null) {
                            parseJson(answer)?.let { return it }
                            if (attempt >= 1) throw AnswerTooLong("Gemini's answer wasn't valid JSON")
                        } else {
                            val reason = JSONObject(reply).optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason").orEmpty()
                            if (reason == "MAX_TOKENS") throw AnswerTooLong("the answer was too long")
                            if (attempt >= 1) throw AnswerTooLong("Gemini returned no answer" + if (reason.isNotEmpty()) " ($reason)" else "")
                        }
                    }
                    code == 598 -> if (++timeouts >= 2) throw AnswerTooLong("no answer within ${timeoutMs / 1000} s")
                    code == 400 && isKeyError(reply) -> throw ClaudeException("Google rejected the API key: " + errorMessage(reply))
                    code == 401 || code == 403 -> throw ClaudeException("Google rejected the API key: " + errorMessage(reply))
                    code == 400 -> { lastError = errorMessage(reply); refused = true }
                    code == 404 -> throw ClaudeException("Gemini model \"$model\" not found: " + errorMessage(reply))
                    code == 429 -> {
                        val msg = errorMessage(reply)
                        if (Regex("per ?day|PerDay|daily", RegexOption.IGNORE_CASE).containsMatchIn(reply))
                            throw ClaudeException("Today's free Gemini limit is used up; try again tomorrow. ($msg)")
                        if (attempt == 5) throw ClaudeException("Gemini is rate limiting: $msg")
                        retryDelay(reply)?.let { wait = it.coerceIn(5_000, 120_000) }
                    }
                    code >= 500 -> if (attempt == 5) throw ClaudeException("Gemini is busy: " + errorMessage(reply))
                    else -> throw ClaudeException("HTTP $code: " + errorMessage(reply))
                }
                if (refused) break
                delay(wait)
                wait = (wait * 2).coerceAtMost(120_000)
            }
            if (!refused) throw ClaudeException("Gemini didn't answer")
        }
        throw ClaudeException("Request refused: $lastError")
    }

    private fun answerText(o: JSONObject): String? {
        val parts = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return null
        val s = (0 until parts.length()).mapNotNull { i -> parts.optJSONObject(i)?.takeUnless { it.optBoolean("thought") }?.optString("text") }
            .joinToString("")
        return s.ifBlank { null }
    }

    private fun parseJson(s: String): JSONObject? {
        val t = s.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        runCatching { return JSONObject(t) }
        val a = t.indexOf('{')
        val b = t.lastIndexOf('}')
        return if (a >= 0 && b > a) runCatching { JSONObject(t.substring(a, b + 1)) }.getOrNull() else null
    }

    private fun isKeyError(reply: String) = "API_KEY_INVALID" in reply || "API key not valid" in reply

    private fun retryDelay(reply: String): Long? =
        Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"").find(reply)?.groupValues?.get(1)?.toLongOrNull()?.let { it * 1000 + 1000 }

    private fun errorMessage(text: String) =
        runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text.take(200))

    private fun post(body: ByteArray, timeoutMs: Int): Pair<Int, String> {
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = timeoutMs
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-goog-api-key", apiKey)
        return try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } catch (e: java.net.SocketTimeoutException) {
            598 to "timed out"
        } catch (e: java.io.IOException) {
            599 to (e.message ?: "network error")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Google's current Flash model: free tier, reads long captions and watches YouTube videos. */
        const val DEFAULT_MODEL = "gemini-3.8-flash"

        fun text(s: String): JSONObject = JSONObject().put("text", s)

        /** A public YouTube video Gemini watches itself (free preview: a few hours of video per day). */
        fun youtube(url: String): JSONObject = JSONObject().put("file_data", JSONObject().put("file_uri", url))
    }
}
