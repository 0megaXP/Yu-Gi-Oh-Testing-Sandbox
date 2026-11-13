package com.ygosandbox.app.combos

import com.ygosandbox.app.platform.platformVideo

/**
 * Reads a video for free with on-device text recognition: a duel log on screen, the narration
 * (captions), or card names shown alone. The reading itself is platform code (ML Kit on
 * Android); this is the shared part: what it returns.
 */
object FreeReader {

    /** One thing read off the screen. [action] is a video-reader action, "use", "turn" or "game_end". */
    data class Event(val t: Double, val code: Long?, val action: String, val player: String?, val exact: Boolean, val text: String)

    class Result(
        /** Everything read, in order. */
        val events: List<Event>,
        /** The video has a duel log (actions and players come from it). */
        val log: Boolean,
        val hand: List<Long>,
        /** What the steps were read from. */
        val source: Source,
    ) {
        /** The cards played, in order (combo steps). */
        val cards: List<Event> get() = events.filter { it.code != null && it.action !in setOf("turn", "game_end", "attack", "draw") }
        val players: List<String> get() = events.mapNotNull { it.player }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.take(2).map { it.key }
    }

    enum class Source { LOG, NARRATION, SCREEN }

    suspend fun read(
        frames: List<Frame>,
        cn: CardNames,
        allowed: Set<Long>,
        handTraps: Set<Long>,
        title: String,
        description: String,
        extraDeck: Set<Long>,
        captions: String = "",
        onProgress: (Float) -> Unit,
    ): Result = platformVideo().readFrames(frames, cn, allowed, handTraps, title, description, extraDeck, captions, onProgress)
}
