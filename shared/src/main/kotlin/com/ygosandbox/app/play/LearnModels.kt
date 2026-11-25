package com.ygosandbox.app.play

import com.ygoengine.LearnReport
import com.ygoengine.LearnStatus
import com.ygoengine.Progress

/** What the computer trains (Windows app): what helps the engine's nightly learning most first. */
enum class TrainMode(val title: String) {
    /** The nightly run's own self-play, with a deeper search: games for the shared network and policy. */
    FORMAT("Play the format's games"),
    /** Opening hands searched deeply: combo lines for the shared pool the nightly bots play from. */
    DEEPEN("Deepen combo lines"),
    /** One deck learns its own network and policy (its bots use them here); its games go to the nightly learning too. */
    DECK("Learn one of my decks"),
}

/**
 * Engine training on this computer (Windows): the modes above, their settings, the session
 * running now and what was done so far.
 */
data class LearnUi(
    val mode: TrainMode = TrainMode.FORMAT,
    /** The deck to learn ([DeckChoice.key]), DECK mode. */
    val deckKey: String? = null,
    /** The decks it plays against; empty: the format's decks the nightly learning plays. */
    val opponents: Set<String> = emptySet(),
    /** The decks whose combo lines are deepened; empty: the format's decks. */
    val deepenDecks: Set<String> = emptySet(),
    /** Search iterations per bot decision (FORMAT mode; the nightly runners use 200 and 800). */
    val depth: Int = 800,
    val minutes: Int = 60,
    /** The folder of the session running now (a deck's key, or [FORMAT_KEY] / [DEEPEN_KEY]), or null. */
    val running: String? = null,
    val runningMode: TrainMode? = null,
    val stopping: Boolean = false,
    val progress: Progress? = null,
    val startedAt: Long = 0,
    val lastReport: LearnReport? = null,
    /** What the last FORMAT / DEEPEN session did, in words. */
    val lastSession: String? = null,
    val error: String? = null,
    /** Games and combo-line batches sent to the engine's nightly learning in this app session, and the last upload's problem. */
    val shared: Int = 0,
    val sharedLines: Int = 0,
    val shareError: String? = null,
    /** Every deck's learning so far, by [DeckChoice.key]. */
    val statuses: Map<String, LearnStatus> = emptyMap(),
    /** On this computer so far: the format's games played, opening hands searched, pieces found. */
    val formatGames: Long = 0,
    val deepenHands: Long = 0,
    val deepenPieces: Long = 0,
) {
    companion object {
        const val FORMAT_KEY = "_format"
        const val DEEPEN_KEY = "_deepen"
    }
}
