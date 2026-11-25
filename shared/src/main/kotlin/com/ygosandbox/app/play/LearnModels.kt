package com.ygosandbox.app.play

import com.ygoengine.LearnReport
import com.ygoengine.LearnStatus
import com.ygoengine.Progress

/**
 * Deck learning (Windows): a deck plays bot matches on this computer and learns its own value
 * network and move policy from them, which its bots then use in the duel simulator.
 */
data class LearnUi(
    /** The deck to learn ([DeckChoice.key]). */
    val deckKey: String? = null,
    /** The decks it plays against; empty: the format's decks the nightly learning plays. */
    val opponents: Set<String> = emptySet(),
    val minutes: Int = 60,
    /** The deck learning now, or null. */
    val running: String? = null,
    val stopping: Boolean = false,
    val progress: Progress? = null,
    val startedAt: Long = 0,
    val lastReport: LearnReport? = null,
    val error: String? = null,
    /** Every deck's learning so far, by [DeckChoice.key]. */
    val statuses: Map<String, LearnStatus> = emptyMap(),
)
