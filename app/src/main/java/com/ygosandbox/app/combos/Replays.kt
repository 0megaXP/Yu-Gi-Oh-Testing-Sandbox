package com.ygosandbox.app.combos

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ---------------------------------------------------------------- what is kept

/** A card used to interrupt: whose it was and what it hit. */
data class Interrupt(val code: Long?, val name: String, val ours: Boolean, val on: String)

/** One turn of a game. "Ours" is the side playing this result's archetype. */
data class TurnRecord(
    val turn: Int,
    val ours: Boolean,
    val summary: String,
    val plays: List<Long>,
    val endBoard: List<Long>,
    val interrupts: List<Interrupt>,
)

data class GameRecord(
    val number: Int,
    /** Null when the video doesn't show who went first. */
    val weFirst: Boolean?,
    /** Null when the video doesn't show the end. */
    val won: Boolean?,
    val how: String,
    val turns: List<TurnRecord>,
    val turningPoints: List<String>,
)

/** A match read from a video, seen from the side playing the archetype. */
data class MatchReplay(
    val url: String,
    val title: String,
    val uploader: String,
    val date: String,
    /** "TCG", "OCG", "Master Duel" or "" when unclear. */
    val format: String,
    val opponent: String,
    val games: List<GameRecord>,
    val sideChanges: List<String>,
    val lessons: List<String>,
    /** Free reading without a duel log: the cards shown, in order (no sides, turns or result). */
    val timeline: List<TimelineEntry> = emptyList(),
) {
    val wins: Int get() = games.count { it.won == true }
    val losses: Int get() = games.count { it.won == false }
}

data class TimelineEntry(val t: Double, val code: Long, val name: String)

data class ReplayLibrary(val deckId: String, val matches: List<MatchReplay>, val dropped: List<DroppedVideo>, val updatedAt: Long) {
    val readUrls: Set<String> get() = (matches.map { it.url } + dropped.map { it.url }).toSet()
}

/** Replays saved per deck id in replays/<id>.json. */
class ReplayStore(context: Context) {
    private val dir = File(context.filesDir, "replays")
    private fun file(deckId: String) = File(dir, deckId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")

    fun load(deckId: String): ReplayLibrary = runCatching { decode(JSONObject(file(deckId).readText())) }
        .getOrDefault(ReplayLibrary(deckId, emptyList(), emptyList(), 0))

    fun save(lib: ReplayLibrary) {
        dir.mkdirs()
        val f = file(lib.deckId)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(encode(lib).toString())
        tmp.renameTo(f)
    }

    private fun JSONArray?.strings() = this?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
    private fun JSONArray?.longs() = this?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList()
    private fun JSONArray?.objects() = this?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    private fun JSONObject.boolOrNull(k: String) = if (isNull(k) || !has(k)) null else optBoolean(k)

    private fun encode(lib: ReplayLibrary) = JSONObject()
        .put("version", 1).put("deckId", lib.deckId).put("updatedAt", lib.updatedAt)
        .put("matches", JSONArray(lib.matches.map { m ->
            JSONObject().put("url", m.url).put("title", m.title).put("uploader", m.uploader).put("date", m.date)
                .put("format", m.format).put("opponent", m.opponent)
                .put("sideChanges", JSONArray(m.sideChanges)).put("lessons", JSONArray(m.lessons))
                .put("timeline", JSONArray(m.timeline.map { JSONObject().put("t", it.t).put("code", it.code).put("name", it.name) }))
                .put("games", JSONArray(m.games.map { g ->
                    JSONObject().put("number", g.number).put("weFirst", g.weFirst ?: JSONObject.NULL)
                        .put("won", g.won ?: JSONObject.NULL).put("how", g.how).put("turningPoints", JSONArray(g.turningPoints))
                        .put("turns", JSONArray(g.turns.map { t ->
                            JSONObject().put("turn", t.turn).put("ours", t.ours).put("summary", t.summary)
                                .put("plays", JSONArray(t.plays)).put("endBoard", JSONArray(t.endBoard))
                                .put("interrupts", JSONArray(t.interrupts.map { i ->
                                    JSONObject().put("code", i.code ?: JSONObject.NULL).put("name", i.name).put("ours", i.ours).put("on", i.on)
                                }))
                        }))
                }))
        }))
        .put("dropped", JSONArray(lib.dropped.map { JSONObject().put("url", it.url).put("title", it.title).put("reason", it.reason) }))

    private fun decode(o: JSONObject) = ReplayLibrary(
        deckId = o.getString("deckId"),
        updatedAt = o.optLong("updatedAt"),
        matches = o.optJSONArray("matches").objects().map { m ->
            MatchReplay(
                url = m.optString("url"), title = m.optString("title"), uploader = m.optString("uploader"),
                date = m.optString("date"), format = m.optString("format"), opponent = m.optString("opponent"),
                sideChanges = m.optJSONArray("sideChanges").strings(), lessons = m.optJSONArray("lessons").strings(),
                timeline = m.optJSONArray("timeline").objects().map { TimelineEntry(it.optDouble("t"), it.optLong("code"), it.optString("name")) },
                games = m.optJSONArray("games").objects().map { g ->
                    GameRecord(
                        number = g.optInt("number"), weFirst = g.boolOrNull("weFirst"), won = g.boolOrNull("won"),
                        how = g.optString("how"), turningPoints = g.optJSONArray("turningPoints").strings(),
                        turns = g.optJSONArray("turns").objects().map { t ->
                            TurnRecord(
                                turn = t.optInt("turn"), ours = t.optBoolean("ours"), summary = t.optString("summary"),
                                plays = t.optJSONArray("plays").longs(), endBoard = t.optJSONArray("endBoard").longs(),
                                interrupts = t.optJSONArray("interrupts").objects().map { i ->
                                    Interrupt(if (i.isNull("code")) null else i.optLong("code"), i.optString("name"), i.optBoolean("ours"), i.optString("on"))
                                },
                            )
                        },
                    )
                },
            )
        },
        dropped = o.optJSONArray("dropped").objects().map { DroppedVideo(it.optString("url"), it.optString("title"), it.optString("reason")) },
    )
}

// ---------------------------------------------------------------- reading

/**
 * Reads a match video with Claude, like [ComboReader] but for whole games and both players.
 * The sides are called A and B while reading; afterwards the one playing the archetype becomes
 * "ours".
 */
class MatchReader(private val claude: ComboReader) {

    data class Event(val t: Double, val side: String, val action: String, val card: String, val confidence: String, val detail: String)

    /** Pass 2's write-up, sides still A / B, card names as read. */
    data class RawMatch(
        val format: String,
        val sideA: String,
        val sideB: String,
        val games: List<RawGame>,
        val sideChanges: List<String>,
        val lessonsA: List<String>,
        val lessonsB: List<String>,
    )

    data class RawGame(val number: Int, val first: String, val winner: String, val how: String, val turns: List<RawTurn>, val turningPoints: List<String>)
    data class RawTurn(val turn: Int, val side: String, val summary: String, val plays: List<String>, val endBoard: List<String>, val interrupts: List<RawInterrupt>)
    data class RawInterrupt(val card: String, val side: String, val on: String)

    suspend fun readBatch(frames: List<Frame>, candidates: List<String>, title: String, captions: String, recent: List<String>, sides: String): List<Event> {
        val content = JSONArray()
        content.put(claude.text("Video: $title\nCards one side is expected to play (this archetype's lists, plus common hand traps):\n" +
            candidates.joinToString("; ")))
        if (sides.isNotEmpty()) content.put(claude.text("Sides so far: $sides"))
        if (recent.isNotEmpty()) content.put(claude.text("Already logged just before these frames:\n" + recent.takeLast(14).joinToString("\n")))
        val cap = claude.captionsAround(captions, frames.first().seconds, frames.last().seconds)
        if (cap.isNotEmpty()) content.put(claude.text("Captions around these frames:\n$cap"))
        frames.forEach { f ->
            content.put(claude.text("Frame t=%.1fs".format(f.seconds)))
            content.put(JSONObject().put("type", "image").put("source", JSONObject()
                .put("type", "base64").put("media_type", "image/jpeg")
                .put("data", Base64.encodeToString(f.file.readBytes(), Base64.NO_WRAP))))
        }
        val out = claude.call(PASS1, content, EVENTS_TOOL, 4000)
        val a = out.optJSONArray("events") ?: JSONArray()
        return (0 until a.length()).map { i ->
            val e = a.getJSONObject(i)
            Event(e.optDouble("t", frames.first().seconds), e.optString("side", "unknown"), e.optString("action"),
                e.optString("card"), e.optString("confidence", "medium"), e.optString("detail"))
        }
    }

    /**
     * Pass 2, in parts so no answer gets too long for a long match: first an outline (the
     * games, who went first and won, sides, side deck changes, takeaways), then each game's
     * turns from the log of that game only.
     */
    suspend fun writeMatch(
        events: List<Event>,
        title: String,
        description: String,
        captions: String,
        archetype: String,
        onPart: (Int, Int) -> Unit = { _, _ -> },
    ): RawMatch {
        fun line(e: Event) = "%.0fs | %s | %s | %s | %s".format(e.t, e.side, e.action, e.card, e.detail)
        fun JSONObject.strings(k: String) = optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }?.filter { it.isNotBlank() } ?: emptyList()
        fun JSONArray?.objects() = this?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

        onPart(0, 0)
        val outlinePrompt = buildString {
            append("Video: $title\nDescription:\n${description.take(3000)}\n\n")
            append("One side plays $archetype.\n\n")
            append("Frame log (time | side | action | card | detail):\n${events.joinToString("\n") { line(it) }.takeLast(60000)}\n\n")
            if (captions.isNotEmpty()) append("Captions (commentary):\n${captions.takeLast(20000)}\n\n")
            append(
                "Outline the match: the games in it (with the times each starts and ends in the video), who went " +
                    "first and who won each and how, the two sides' players and decks, side deck changes between " +
                    "games, and for each side the lessons a player of that deck can take. Leave out what the video " +
                    "doesn't show."
            )
        }
        val o = claude.call("You outline a Yu-Gi-Oh! match video from its log.", JSONArray().put(claude.text(outlinePrompt)),
            OUTLINE_TOOL, 4000, timeoutMs = 300_000)
        val sides = o.optJSONObject("sides") ?: JSONObject()
        var outline = o.optJSONArray("games").objects()
        val lastT = events.maxOfOrNull { it.t } ?: 0.0
        if (outline.isEmpty())  // nothing outlined: treat the whole video as one game
            outline = listOf(JSONObject().put("number", 1).put("start", 0).put("end", lastT).put("first", "unknown").put("winner", "unknown"))

        val games = outline.mapIndexed { gi, g ->
            onPart(gi + 1, outline.size)
            val t0 = g.optDouble("start", 0.0) - 5
            val t1 = (if (g.has("end")) g.optDouble("end", lastT) else lastT) + 5
            val log = events.filter { it.t in t0..t1 }
            val prompt = buildString {
                append("Video: $title\nOne side plays $archetype. This is game ${g.optInt("number", gi + 1)}, ")
                append("${g.optString("first", "unknown")} went first, ${g.optString("winner", "unknown")} won.\n\n")
                append("Frame log of this game (time | side | action | card | detail):\n${log.joinToString("\n") { line(it) }.takeLast(60000)}\n\n")
                val cap = claude.captionsAround(captions, t0, t1).takeLast(15000)
                if (cap.isNotEmpty()) append("Captions:\n$cap\n\n")
                append(
                    "Write every turn of this game: a one-line summary, the main cards played, the board that side " +
                        "ended on, and the interruptions used (by whom, on what). Then the turning points. Use exact " +
                        "card names."
                )
            }
            val r = runCatching {
                claude.call("You write up one game of a Yu-Gi-Oh! match from its log.", JSONArray().put(claude.text(prompt)),
                    GAME_TOOL, 8000, timeoutMs = 300_000)
            }.getOrElse { e -> if (e is AnswerTooLong) JSONObject() else throw e }  // a game too long to write up keeps its outline
            RawGame(
                number = g.optInt("number", gi + 1), first = g.optString("first", "unknown"), winner = g.optString("winner", "unknown"),
                how = g.optString("how"), turningPoints = r.strings("turning_points"),
                turns = r.optJSONArray("turns").objects().map { t ->
                    RawTurn(
                        turn = t.optInt("turn"), side = t.optString("side"), summary = t.optString("summary"),
                        plays = t.strings("plays"), endBoard = t.strings("end_board"),
                        interrupts = t.optJSONArray("interruptions").objects().map { i ->
                            RawInterrupt(i.optString("card"), i.optString("side"), i.optString("on"))
                        },
                    )
                },
            )
        }
        return RawMatch(
            format = o.optString("format", "unknown"),
            sideA = sides.optString("A"),
            sideB = sides.optString("B"),
            games = games,
            sideChanges = o.strings("side_changes"),
            lessonsA = o.optJSONObject("lessons")?.strings("A") ?: emptyList(),
            lessonsB = o.optJSONObject("lessons")?.strings("B") ?: emptyList(),
        )
    }

    companion object {
        private const val PASS1 =
            "You are reading frames from a Yu-Gi-Oh! match video: a feature match, a tournament round or a ranked replay " +
                "(Master Duel, EDOPro, Dueling Nexus, Duelingbook or a real table). Call the two players A and B and keep them " +
                "consistent: A is the player at the bottom of the screen (or on the left / nearer the camera), B the other. " +
                "Log every game action you can see with its side: activations, summons (with materials), searches, cards sent " +
                "to the GY, banished, attached, attacks, life point changes, and the end of each game (action 'game_end', with " +
                "the winner's side in detail). Read card names from the card panel, the activation banner, the duel log or the " +
                "commentary, and use exact names. Frames are consecutive screens of one video, so don't log an action twice " +
                "while it stays on screen. If nothing happens in these frames, return no events."

        private val EVENTS_TOOL: JSONObject = JSONObject()
            .put("name", "log_match_events")
            .put("description", "Log what happens on screen in these frames, in order, with each action's side.")
            .put("input_schema", ComboReader.schema(
                "events" to ComboReader.arr(ComboReader.schema(
                    "t" to JSONObject().put("type", "number"),
                    "side" to ComboReader.enum(listOf("A", "B", "unknown")),
                    "action" to ComboReader.enum(ComboReader.ACTIONS + listOf("life_points", "game_end", "side_deck")),
                    "card" to ComboReader.str("exact card name; '' if none"),
                    "detail" to ComboReader.str("materials, targets, what it hit, LP, the winner at game_end"),
                    "confidence" to ComboReader.enum(listOf("high", "medium", "low")),
                    required = listOf("t", "side", "action", "card", "confidence"),
                )),
                "sides" to ComboReader.str("how to tell A and B apart, e.g. 'A: bottom, Snake-Eye; B: top, Angelechy'"),
                required = listOf("events"),
            ))

        private val INTERRUPT = ComboReader.schema(
            "card" to ComboReader.str(),
            "side" to ComboReader.enum(listOf("A", "B")),
            "on" to ComboReader.str("what it hit"),
            required = listOf("card", "side"),
        )

        private val OUTLINE_TOOL: JSONObject = JSONObject()
            .put("name", "outline_match")
            .put("description", "Outline the match shown in the video.")
            .put("input_schema", ComboReader.schema(
                "format" to ComboReader.enum(listOf("TCG", "OCG", "Master Duel", "unknown")),
                "sides" to ComboReader.schema(
                    "A" to ComboReader.str("player and deck"), "B" to ComboReader.str("player and deck"),
                    required = listOf("A", "B"),
                ),
                "games" to ComboReader.arr(ComboReader.schema(
                    "number" to JSONObject().put("type", "integer"),
                    "start" to JSONObject().put("type", "number").put("description", "second of the video the game starts"),
                    "end" to JSONObject().put("type", "number").put("description", "second of the video the game ends"),
                    "first" to ComboReader.enum(listOf("A", "B", "unknown")),
                    "winner" to ComboReader.enum(listOf("A", "B", "unknown")),
                    "how" to ComboReader.str("how the game was won, one sentence"),
                    required = listOf("number", "start", "first", "winner"),
                )),
                "side_changes" to ComboReader.arr(ComboReader.str(), "cards sided in / out between games, if shown"),
                "lessons" to ComboReader.schema(
                    "A" to ComboReader.arr(ComboReader.str()), "B" to ComboReader.arr(ComboReader.str()),
                    required = listOf(),
                ),
                required = listOf("sides", "games"),
            ))

        private val GAME_TOOL: JSONObject = JSONObject()
            .put("name", "write_game")
            .put("description", "Write up one game turn by turn.")
            .put("input_schema", ComboReader.schema(
                "turns" to ComboReader.arr(ComboReader.schema(
                    "turn" to JSONObject().put("type", "integer"),
                    "side" to ComboReader.enum(listOf("A", "B")),
                    "summary" to ComboReader.str("one line"),
                    "plays" to ComboReader.arr(ComboReader.str(), "main cards played"),
                    "end_board" to ComboReader.arr(ComboReader.str(), "that side's field at the end of the turn"),
                    "interruptions" to ComboReader.arr(INTERRUPT),
                    required = listOf("turn", "side", "summary"),
                )),
                "turning_points" to ComboReader.arr(ComboReader.str()),
                required = listOf("turns"),
            ))
    }
}
