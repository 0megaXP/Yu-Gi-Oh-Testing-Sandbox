package com.ygosandbox.app.combos

import com.ygosandbox.app.combos.ComboReader.Companion.arr
import com.ygosandbox.app.combos.ComboReader.Companion.enum
import com.ygosandbox.app.combos.ComboReader.Companion.schema
import com.ygosandbox.app.combos.ComboReader.Companion.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a deck guide (deck profile, "how to play", combo explanation) from its captions and
 * description in one call: what the deck wants to do, its starters and extenders,
 * what to search first, what to protect, where it is stopped, card choices, explained combos and
 * tips, with Claude or with Google's Gemini (free tier). Card names are resolved against the archetype's card pool afterwards.
 */
class GuideReader private constructor(private val claude: ComboReader?, private val gemini: GeminiClient?) {

    /** Can watch a video without captions itself (Gemini reads public YouTube videos). */
    val watches: Boolean get() = gemini != null

    /**
     * [watchUrl]: a video with no captions, for a reader that [watches] it. Gemini then watches
     * it at low resolution, which the free tier allows for a few hours of video a day.
     */
    suspend fun read(
        archetype: String,
        title: String,
        uploader: String,
        description: String,
        captions: String,
        pool: List<String>,
        watchUrl: String? = null,
    ): JSONObject {
        val prompt = buildString {
            append("Archetype: $archetype\nVideo: \"$title\" by $uploader\n\n")
            append("Card names in this archetype's tournament lists (use these exact names):\n")
            append(pool.joinToString("; "))
            append("\n\n")
            if (description.isNotBlank()) append("Video description:\n${description.take(4000)}\n\n")
            if (watchUrl != null) append("The video has no captions: listen to it and read the cards on screen.")
            else {
                // ~2 hours of speech fits; longer captions keep their start, where guides explain the basics
                append("Captions (what the video says, [mm:ss] timestamps; auto-captions mishear card names):\n")
                append(captions.take(90_000))
            }
        }
        gemini?.let { g ->
            val parts = JSONArray()
            if (watchUrl != null) parts.put(GeminiClient.youtube(watchUrl))
            parts.put(GeminiClient.text(prompt))
            return g.json(SYSTEM, parts, TOOL.getJSONObject("input_schema"), lowMedia = watchUrl != null)
        }
        return claude!!.call(SYSTEM, JSONArray().put(claude.text(prompt)), TOOL, maxTokens = 8000, timeoutMs = 240_000)
    }

    companion object {
        fun claude(apiKey: String, model: String) = GuideReader(ComboReader(apiKey, model), null)
        fun gemini(apiKey: String, model: String) = GuideReader(null, GeminiClient(apiKey, model))

        private const val SYSTEM =
            "You read Yu-Gi-Oh! deck guides (deck profiles, how-to-play and combo explanations) from their captions, " +
                "to teach a game engine to play the deck well. Only report what the video says or clearly shows in " +
                "what it says; do not add general knowledge about the deck. Auto-captions mishear card names " +
                "(\"angel echy\" for Angelechy, \"ash\" for Ash Blossom): map them to the exact names in the card list " +
                "when the match is clear, otherwise give the name as you understood it. Keep notes short (one sentence). " +
                "If the video is not a guide for this archetype (a pack opening, an unrelated deck, a match with no " +
                "explanation), set relevant to false and leave the lists empty."

        private fun cards(desc: String) = arr(schema(
            "card" to str("exact card name"),
            "note" to str("why, in one short sentence, as the video says it"),
            required = listOf("card"),
        ), desc)

        private val TOOL: JSONObject = JSONObject()
            .put("name", "write_guide")
            .put("description", "Write down what this guide teaches about playing the deck.")
            .put("input_schema", schema(
                "relevant" to JSONObject().put("type", "boolean").put("description", "is this a guide for the archetype"),
                "summary" to str("two sentences: what the video covers"),
                "strategy" to str("how the deck wins and what it wants to do on turn 1 and going second, as explained"),
                "starters" to cards("one-card starters / best cards to open"),
                "extenders" to cards("cards that keep a combo going after an interruption, or add to it"),
                "search_first" to cards("cards to search or add first, in the order the video recommends (best first)"),
                "protect" to cards("cards that should not be spent as cost, material or discard; the deck needs to keep them"),
                "choke_points" to cards("this deck's own cards where hand traps or interruptions stop it best"),
                "weak_to" to cards("opponent cards (hand traps, floodgates, board breakers) the deck struggles against"),
                "build_notes" to cards("card choices and ratios explained (how many copies, tech cards, side deck)"),
                "combos" to arr(schema(
                    "name" to str("e.g. 'one-card combo off X'"),
                    "going" to enum(listOf("first", "second", "unknown")),
                    "starting_hand" to arr(str()),
                    "steps" to arr(schema(
                        "action" to enum(ComboReader.ACTIONS),
                        "card" to str(),
                        "detail" to str("materials, cost, what it searches or sends"),
                        required = listOf("action", "card"),
                    )),
                    "end_board" to arr(schema(
                        "card" to str(),
                        "where" to str("monster zone, spell/trap zone, field zone, hand, gy"),
                        required = listOf("card", "where"),
                    )),
                    required = listOf("starting_hand", "steps"),
                ), "combos walked through step by step"),
                "tips" to arr(str(), "other advice on playing the deck (sequencing, playing around hand traps, going second)"),
                required = listOf("relevant", "summary", "starters", "extenders", "search_first", "protect", "choke_points", "combos", "tips"),
            ))
    }
}
