package com.ygosandbox.app.combos

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ClaudeException(message: String) : Exception(message)

/** No usable answer for this video (too long, too slow, or none): it fails, the next ones still run. */
class AnswerTooLong(message: String) : Exception(message)

/**
 * Reads combo-video frames with Claude, in two passes:
 * 1. frames in small batches, in order, logging every action on screen;
 * 2. that log plus the captions and the description, written up as one combo.
 * Both force a tool call so the answer always comes back as JSON.
 */
class ComboReader(private val apiKey: String, private val model: String) {

    /** One logged action from pass 1. */
    data class Event(val t: Double, val action: String, val card: String, val player: String, val confidence: String, val detail: String)

    /** Pass 2's write-up, card names as Claude read them. */
    data class RawCombo(
        val going: String,
        val hand: List<String>,
        val steps: List<RawStep>,
        val endBoard: List<Pair<String, String>>, // card, where
        val interruptions: List<String>,
        val chokePoints: List<String>,
        val uncertain: List<String>,
    )

    data class RawStep(val t: Double?, val action: String, val card: String, val detail: String)

    suspend fun readBatch(
        frames: List<Frame>,
        candidates: List<String>,
        title: String,
        captions: String,
        recent: List<String>,
    ): List<Event> {
        val content = JSONArray()
        content.put(text("Video: $title\nCandidate cards (this deck's lists, plus common hand traps):\n" + candidates.joinToString("; ")))
        if (recent.isNotEmpty()) content.put(text("Already logged just before these frames:\n" + recent.takeLast(12).joinToString("\n")))
        val cap = captionsAround(captions, frames.first().seconds, frames.last().seconds)
        if (cap.isNotEmpty()) content.put(text("Captions around these frames:\n$cap"))
        frames.forEach { f ->
            content.put(text("Frame t=%.1fs".format(f.seconds)))
            content.put(JSONObject().put("type", "image").put("source", JSONObject()
                .put("type", "base64").put("media_type", "image/jpeg")
                .put("data", Base64.encodeToString(f.file.readBytes(), Base64.NO_WRAP))))
        }
        val out = call(PASS1_SYSTEM, content, EVENTS_TOOL, 4000)
        val events = out.optJSONArray("events") ?: JSONArray()
        return (0 until events.length()).map { i ->
            val e = events.getJSONObject(i)
            Event(e.optDouble("t", frames.first().seconds), e.optString("action"), e.optString("card"),
                e.optString("player", "unknown"), e.optString("confidence", "medium"), e.optString("detail"))
        }
    }

    suspend fun writeCombo(events: List<Event>, candidates: List<String>, title: String, description: String, captions: String): RawCombo {
        val log = events.joinToString("\n") {
            "%.1fs | %s | %s | %s | %s | %s".format(it.t, it.player, it.action, it.card, it.detail, it.confidence)
        }
        val prompt = buildString {
            append("Video: $title\nDescription:\n${description.take(3000)}\n\n")
            append("Candidate cards:\n${candidates.joinToString("; ")}\n\n")
            append("Frame log (time | player | action | card | detail | confidence):\n$log\n\n")
            if (captions.isNotEmpty()) append("Captions:\n${captions.take(15000)}\n\n")
            append(
                "Write the combo as one clean line of play: the starting hand, each step in order, and the end board. " +
                    "Steps are only the combo player's own actions; opponent cards (hand traps the video shows as " +
                    "'what if') go in choke_points. Merge duplicate log entries, drop menu noise, and fix the order " +
                    "where the log is clearly out of order. If the video shows several combos or hands, write the " +
                    "first full one and mention the others in 'uncertain'. Use exact card names."
            )
        }
        val o = call("You turn a Yu-Gi-Oh! video log into a precise combo write-up.", JSONArray().put(text(prompt)), COMBO_TOOL, 8000)
        fun strings(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }?.filter { it.isNotBlank() } ?: emptyList()
        val steps = o.optJSONArray("steps") ?: JSONArray()
        val board = o.optJSONArray("end_board") ?: JSONArray()
        return RawCombo(
            going = o.optString("going", "unknown"),
            hand = strings("starting_hand"),
            steps = (0 until steps.length()).map { i ->
                val s = steps.getJSONObject(i)
                RawStep(if (s.has("t")) s.optDouble("t") else null, s.optString("action"), s.optString("card"), s.optString("detail"))
            },
            endBoard = (0 until board.length()).map { i -> board.getJSONObject(i).let { it.optString("card") to it.optString("where") } },
            interruptions = strings("interruptions"),
            chokePoints = strings("choke_points"),
            uncertain = strings("uncertain"),
        )
    }

    internal fun text(s: String) = JSONObject().put("type", "text").put("text", s)

    internal fun captionsAround(captions: String, t0: Double, t1: Double): String =
        captions.lineSequence().filter { line ->
            val m = Regex("""^\[(\d+):(\d\d)]""").find(line) ?: return@filter false
            val t = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
            t >= t0 - 8 && t <= t1 + 8
        }.joinToString("\n")

    /** One Messages API call with a forced tool; retries rate limits and overloads. */
    internal suspend fun call(
        system: String,
        content: JSONArray,
        tool: JSONObject,
        maxTokens: Int,
        /** How long to wait for the whole answer; long write-ups need more. */
        timeoutMs: Int = 180_000,
    ): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("tools", JSONArray().put(tool))
            .put("tool_choice", JSONObject().put("type", "tool").put("name", tool.getString("name")))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .toString().toByteArray()
        var wait = 4_000L
        var timeouts = 0
        repeat(5) { attempt ->
            val (code, text) = withContext(Dispatchers.IO) { post(body, timeoutMs) }
            when {
                code in 200..299 -> {
                    val o = JSONObject(text)
                    // cut off at max_tokens: asking again gives the same; the caller splits the work
                    if (o.optString("stop_reason") == "max_tokens") throw AnswerTooLong("the answer was longer than $maxTokens tokens")
                    val blocks = o.optJSONArray("content") ?: JSONArray()
                    for (i in 0 until blocks.length()) {
                        val b = blocks.getJSONObject(i)
                        if (b.optString("type") == "tool_use") return b.getJSONObject("input")
                    }
                    if (attempt == 4) throw AnswerTooLong("Claude returned no usable answer for this video")
                }
                code == 598 -> if (++timeouts >= 2) throw AnswerTooLong("no answer within ${timeoutMs / 1000} s")
                code == 401 -> throw ClaudeException("The API key was rejected (check it in the Combos tab settings)")
                code == 400 || code == 404 -> throw ClaudeException("Request refused: " + errorMessage(text))
                code == 429 || code >= 500 -> if (attempt == 4) throw ClaudeException("Claude is busy: " + errorMessage(text))
                else -> throw ClaudeException("HTTP $code: " + errorMessage(text))
            }
            delay(wait)
            wait *= 2
        }
        throw ClaudeException("Claude didn't answer")
    }

    private fun errorMessage(text: String) =
        runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text.take(200))

    private fun post(body: ByteArray, timeoutMs: Int): Pair<Int, String> {
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = timeoutMs
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        return try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } catch (e: java.net.SocketTimeoutException) {
            598 to "timed out" // the answer took too long: tried once more, then the caller is told
        } catch (e: java.io.IOException) {
            599 to (e.message ?: "network error") // retried like an overload
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"

        internal val ACTIONS = listOf(
            "normal_summon", "special_summon", "link_summon", "xyz_summon", "synchro_summon", "fusion_summon",
            "ritual_summon", "pendulum_summon", "set", "activate", "chain", "resolve", "search", "draw", "send_to_gy",
            "banish", "attach", "return", "negate", "attack", "phase", "hand_shown", "board_shown", "other",
        )

        private const val PASS1_SYSTEM =
            "You are reading frames from a Yu-Gi-Oh! combo video, usually recorded in EDOPro, Master Duel, Dueling Nexus " +
                "or Duelingbook, sometimes a real table. Log every game action you can see: activations, summons (with " +
                "materials), searches, cards sent to the GY, banished, attached, and the chain. Mark each action's player: " +
                "'combo' for the player doing the combo, 'opponent' for anything the other side does. Read card names from " +
                "the card art panel, the card text box, the activation banner and the duel log, and use exact names; when a " +
                "name is partly hidden, pick the matching card from the candidates, but never force a card onto the " +
                "candidates if the screen clearly shows another card. Frames are consecutive screens of one video, so don't " +
                "log an action twice while it stays on screen. If nothing happens in these frames, return no events."

        internal fun schema(vararg props: Pair<String, JSONObject>, required: List<String>) = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
            .put("required", JSONArray(required))

        internal fun str(desc: String? = null) = JSONObject().put("type", "string").apply { desc?.let { put("description", it) } }
        internal fun enum(values: List<String>) = JSONObject().put("type", "string").put("enum", JSONArray(values))
        internal fun arr(items: JSONObject, desc: String? = null) =
            JSONObject().put("type", "array").put("items", items).apply { desc?.let { put("description", it) } }

        private val EVENTS_TOOL: JSONObject = JSONObject()
            .put("name", "log_events")
            .put("description", "Log what happens on screen in these frames, in order.")
            .put("input_schema", schema(
                "events" to arr(schema(
                    "t" to JSONObject().put("type", "number").put("description", "seconds, from the frame label"),
                    "action" to enum(ACTIONS),
                    "card" to str("exact card name as shown; '' if none"),
                    "player" to enum(listOf("combo", "opponent", "unknown")),
                    "detail" to str("materials, cost, targets, which effect, chain link"),
                    "confidence" to enum(listOf("high", "medium", "low")),
                    required = listOf("t", "action", "card", "player", "confidence"),
                )),
                required = listOf("events"),
            ))

        private val COMBO_TOOL: JSONObject = JSONObject()
            .put("name", "write_combo")
            .put("description", "Write the combo shown in the video.")
            .put("input_schema", schema(
                "going" to enum(listOf("first", "second", "unknown")),
                "starting_hand" to arr(str()),
                "steps" to arr(schema(
                    "t" to JSONObject().put("type", "number"),
                    "action" to enum(ACTIONS),
                    "card" to str(),
                    "detail" to str(),
                    required = listOf("action", "card", "detail"),
                )),
                "end_board" to arr(schema(
                    "card" to str(),
                    "where" to str("monster zone, spell/trap zone, field zone, hand, gy"),
                    required = listOf("card", "where"),
                )),
                "interruptions" to arr(str(), "interruptions the creator says the end board has"),
                "choke_points" to arr(str(), "where hand traps hit, as the creator says or as you can see"),
                "uncertain" to arr(str(), "anything you could not read or had to infer"),
                required = listOf("starting_hand", "steps", "end_board", "uncertain"),
            ))
    }
}
