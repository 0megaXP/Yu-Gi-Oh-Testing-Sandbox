package com.ygosandbox.app.play

import com.ygoengine.BotLevel
import com.ygoengine.DuelActivation
import com.ygoengine.DuelMove
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

/** One log line and the player it's about (engine index 0/1, or -1 for nobody). */
data class LogEntry(val player: Int, val text: String)

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
    val log: List<LogEntry> = emptyList(),
    /** Cards that moved in the latest update, for the board animation. */
    val moves: List<DuelMove> = emptyList(),
    /** Cards and effects activated in the latest update, for the activation effect. */
    val activations: List<DuelActivation> = emptyList(),
    /** Cards targeted or picked by effects in the latest update, for the target effect. */
    val picks: List<com.ygoengine.DuelPick> = emptyList(),
    /** Goes up with every update that has moves, activations or picks, so the board knows to animate them. */
    val moveBatch: Int = 0,
    /** For saving the duel as a replay: the engine's setup (null with an engine that can't) and every answer so far. */
    val replaySetup: String? = null,
    val replayAnswers: List<String> = emptyList(),
    /** The whole log as the engine wrote it ("[P0]"/"[P1]"), for the replay file. */
    val rawLog: List<String> = emptyList(),
    val paused: Boolean = false,
    val thinking: Boolean = false,
    val error: String? = null,
)
