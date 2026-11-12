package com.ygosandbox.app.play

import com.ygoengine.BotLevel
import com.ygoengine.DuelState

enum class DuelMode(val label: String) { HUMAN_VS_BOT("You vs bot"), BOT_VS_BOT("Bot vs bot") }

enum class FirstPlayer(val label: String) { ME("Deck 1 first"), THEM("Deck 2 first"), RANDOM("Random") }

/** Duel simulator settings, kept in the ViewModel. "Deck 1" is you (or bot 1), shown at the bottom. */
data class DuelSetup(
    val mode: DuelMode = DuelMode.HUMAN_VS_BOT,
    val deck1: String? = null,
    val deck2: String? = null,
    val first: FirstPlayer = FirstPlayer.RANDOM,
    val level1: BotLevel = BotLevel.NORMAL,
    val level2: BotLevel = BotLevel.NORMAL,
    /** Pause before every bot move, so you can follow the game. */
    val delayMs: Long = 900,
    /** Bot vs bot: show both hands and set cards. */
    val reveal: Boolean = true,
)

data class DuelUi(
    val setup: DuelSetup = DuelSetup(),
    /** A duel is on (or just finished) — show the board. */
    val active: Boolean = false,
    val starting: Boolean = false,
    val state: DuelState? = null,
    /** Engine player index (0 = went first) shown at the bottom of the board. */
    val bottom: Int = 0,
    /** Engine player index you control, or -1 in bot vs bot. */
    val human: Int = -1,
    val names: List<String> = listOf("Player 1", "Player 2"),
    val levels: List<BotLevel> = listOf(BotLevel.NORMAL, BotLevel.NORMAL),
    /** Whole game log, oldest first, with player names filled in. */
    val log: List<String> = emptyList(),
    val paused: Boolean = false,
    val thinking: Boolean = false,
    val error: String? = null,
)
