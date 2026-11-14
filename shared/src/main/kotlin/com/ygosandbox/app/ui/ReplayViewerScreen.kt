@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ygosandbox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ygosandbox.app.AppViewModel
import com.ygosandbox.app.history.MatchHistory
import com.ygosandbox.app.history.ReplayPlayback
import com.ygosandbox.app.history.ReplayStep
import com.ygosandbox.app.model.GameFormat
import com.ygosandbox.app.play.DuelUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Watches one YGO Omega replay on the duel simulator's board, one action at a time: Next move
 * plays the next action (with its animation), Previous move goes back one.
 */
@Composable
fun ReplayViewerScreen(vm: AppViewModel, gameId: String) {
    val game = remember(gameId) { MatchHistory.game(gameId) }
    val me = game?.me ?: 0
    val names = remember(game) {
        List(2) { p ->
            val deck = game?.archetypes?.getOrNull(p).orEmpty()
            (if (p == me) "You" else "Opponent") + if (deck.isNotBlank()) " ($deck)" else ""
        }
    }
    var steps by remember(gameId) { mutableStateOf<List<ReplayStep>?>(null) }
    var problem by remember(gameId) { mutableStateOf<String?>(null) }
    var index by rememberSaveable(gameId) { mutableStateOf(0) }
    // goes up on every step forward, so the board animates that step's moves
    var batch by remember(gameId) { mutableStateOf(0) }
    var forward by remember(gameId) { mutableStateOf(false) }
    var pager by remember { mutableStateOf<Pair<List<Long>, Int>?>(null) }
    var playing by remember(gameId) { mutableStateOf(false) }

    LaunchedEffect(gameId) {
        if (game == null) { problem = "This game isn't in the match history any more."; return@LaunchedEffect }
        val result = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = MatchHistory.bytesOf(game) ?: error("The replay file isn't in the replay folder any more.")
                ReplayPlayback.build(bytes, vm.db, names)
            }
        }
        result.onSuccess { s ->
            steps = s
            index = index.coerceIn(0, (s.size - 1).coerceAtLeast(0))
            if (s.isEmpty()) problem = "Nothing to show in this replay."
        }.onFailure { e -> problem = e.message ?: e.javaClass.simpleName }
    }

    val all = steps
    if (all == null || all.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            Header("Replay", onBack = { vm.back() })
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (problem == null) {
                    Text("Reading the replay…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else Text(problem!!)
            }
        }
        return
    }

    val step = all[index]
    val log = remember(all, index) { all.take(index + 1).flatMap { it.log } }
    val ui = DuelUi(
        active = true,
        state = step.state,
        bottom = me,
        human = me,
        names = names,
        log = log,
        moves = if (forward) step.state.moves else emptyList(),
        activations = if (forward) step.state.activations else emptyList(),
        moveBatch = batch,
    )
    fun go(to: Int) {
        val t = to.coerceIn(0, all.size - 1)
        if (t == index) return
        forward = t == index + 1
        if (forward) batch++
        index = t
    }

    // Auto play: the next move one second after the last one's animations end, until the last move.
    LaunchedEffect(playing, index) {
        if (!playing) return@LaunchedEffect
        if (index >= all.size - 1) { playing = false; return@LaunchedEffect }
        delay(400) // lets this move's animations start and report how long they take
        val busy = (vm.duelAnimationsUntil - System.currentTimeMillis()).coerceAtLeast(0L)
        delay(busy + 600)
        go(index + 1)
    }

    Box(Modifier.fillMaxSize()) {
        DuelBoardScreen(
            ui, vm, onOpen = { codes, i -> pager = codes to i },
            title = "Replay · Turn ${step.state.turn} · ${step.state.phase}",
            actions = {},
            controls = { _ ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        step.log.lastOrNull()?.text ?: "",
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(enabled = index > 0, onClick = { playing = false; go(index - 1) }) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                            Text("Previous move")
                        }
                        Button(enabled = index < all.size - 1, onClick = { playing = false; go(index + 1) }) {
                            Text("Next move")
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                        // the same button starts and stops auto play
                        FilledTonalButton(enabled = playing || index < all.size - 1, onClick = { playing = !playing }) {
                            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                            Text(if (playing) "Stop" else "Auto play")
                        }
                    }
                    Text(
                        "Move ${index + 1} of ${all.size}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        pager?.let { (codes, start) ->
            CardPager(
                codes.map { PagerItem(it, vm.db.find(it)?.name ?: "Unknown card", vm.db.find(it)) },
                start, GameFormat.TCG, onClose = { pager = null },
            )
        }
    }
}
