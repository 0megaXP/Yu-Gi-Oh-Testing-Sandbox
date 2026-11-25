@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ygosandbox.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ygoengine.LearnRound
import com.ygoengine.LearnStatus
import com.ygosandbox.app.AppViewModel
import com.ygosandbox.app.UiState
import com.ygosandbox.app.play.DeckChoice

private val DURATIONS = listOf(30 to "30 min", 60 to "1 h", 120 to "2 h", 240 to "4 h", 480 to "8 h")

/**
 * Deck learning (Windows): pick a deck, who it plays against and for how long; the engine plays
 * bot matches with it on this computer and learns its own network and move policy, which its
 * bots then use in the duel simulator.
 */
@Composable
fun LearnScreen(ui: UiState, vm: AppViewModel) {
    val l = ui.learn
    val choices = remember(ui.myDecks, ui.decks) { vm.playDeckChoices() }
    LaunchedEffect(Unit) { vm.refreshLearnStatuses() }
    Column(Modifier.fillMaxSize()) {
        Header("Deck learning", onBack = { vm.back() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!vm.canLearn) {
                Text("Deck learning runs on the Windows app.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text(
                "The engine plays best-of-3 bot matches with your deck on this computer, then trains a network and a " +
                    "move policy of its own for it from those games. It keeps them only when they predict the games better " +
                    "and do at least as well as before in a test on the same deals. The deck's bots in the duel simulator " +
                    "then play with what it learned. Keep the app open and the computer awake while it runs.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (l.running != null) RunningCard(l, choices, vm)

            val enabled = l.running == null
            Section("Deck to learn") {
                DeckTagPicker(
                    field = if (l.deckKey == null) "Type a deck or archetype" else "Type to pick another deck",
                    choices = choices, selected = listOfNotNull(l.deckKey), enabled = enabled,
                    onAdd = vm::setLearnDeck, onRemove = { vm.setLearnDeck(null) },
                )
                l.deckKey?.let { key -> l.statuses[key]?.takeIf { it.games > 0 }?.let { st ->
                    Text(
                        "Already learned from ${st.games} games" + if (st.learned) "; it keeps going from there." else ".",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } }
            }
            Section("Plays against") {
                DeckTagPicker(
                    field = "Add an opponent deck or archetype",
                    choices = choices.filter { it.key != l.deckKey }, selected = l.opponents.toList(), enabled = enabled,
                    onAdd = vm::toggleLearnOpponent, onRemove = vm::toggleLearnOpponent,
                )
                Text(
                    if (l.opponents.isEmpty()) "None picked: it plays the format's decks, the best decks from recent top cuts that the nightly learning plays."
                    else "${l.opponents.size} deck${if (l.opponents.size == 1) "" else "s"}, one picked at random for each match.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Section("For") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DURATIONS.forEach { (m, label) ->
                        FilterChip(selected = l.minutes == m, enabled = enabled, onClick = { vm.setLearnMinutes(m) }, label = { Text(label) })
                    }
                }
                Text(
                    "In rounds of up to 40 minutes: about 70% playing, then training, then the test. A longer session " +
                        "learns from more games; every session continues from the last.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            l.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (enabled) Button(onClick = { vm.startLearning() }, enabled = l.deckKey != null) { Text("Start learning") }

            l.lastReport?.let { r ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Text(
                        "Last session: ${r.games} games in ${r.rounds} round${if (r.rounds == 1) "" else "s"}" +
                            (if (r.cancelled) ", stopped early" else "") + ". " +
                            when {
                                r.keptNetworks + r.keptPolicies == 0 -> "Nothing new was kept this time: it didn't beat what the deck had."
                                else -> listOfNotNull(
                                    if (r.keptNetworks > 0) "a new network" else null,
                                    if (r.keptPolicies > 0) "a new move policy" else null,
                                ).joinToString(" and ").replaceFirstChar { it.uppercase() } + " kept."
                            },
                        modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            val learned = l.statuses.entries.sortedByDescending { it.value.updated }
            if (learned.isNotEmpty()) {
                Text("Learned on this computer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                learned.forEach { (key, st) ->
                    LearnedDeck(choices.firstOrNull { it.key == key }?.name ?: "A deck no longer in the app", st,
                        running = l.running == key, onForget = { vm.forgetLearning(key) })
                }
            }
            Spacer(Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        content()
    }
}

/**
 * Decks as tags, like the archetype search: type part of a deck's name or archetype and pick it
 * from the suggestions; a tag's ✕ removes it.
 */
@Composable
private fun DeckTagPicker(
    field: String,
    choices: List<DeckChoice>,
    selected: List<String>,
    enabled: Boolean,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val suggestions = remember(query, choices, selected) {
        val q = query.trim()
        if (q.isEmpty()) emptyList()
        else {
            val pool = choices.filter { it.key !in selected }
            (pool.filter { it.name.startsWith(q, ignoreCase = true) } +
                pool.filter { it.name.contains(q, ignoreCase = true) } +
                pool.filter { it.detail.contains(q, ignoreCase = true) })
                .distinctBy { it.key }.take(8)
        }
    }
    if (selected.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            selected.forEach { key ->
                val c = choices.firstOrNull { it.key == key }
                InputChip(
                    selected = true, enabled = enabled,
                    onClick = { onRemove(key) },
                    label = { Text(c?.name ?: "A deck no longer in the app", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(InputChipDefaults.IconSize)) },
                )
            }
        }
    }
    if (!enabled) return
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(field) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            suggestions.firstOrNull()?.let { onAdd(it.key); query = "" }
        }),
        modifier = Modifier.fillMaxWidth(),
    )
    if (suggestions.isNotEmpty()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
            Column {
                suggestions.forEachIndexed { i, c ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        Modifier.fillMaxWidth().clickable { onAdd(c.key); query = "" }.padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    } else if (query.isNotBlank()) {
        Text("No deck matches \"${query.trim()}\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RunningCard(l: com.ygosandbox.app.play.LearnUi, choices: List<DeckChoice>, vm: AppViewModel) {
    val p = l.progress
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Learning ${choices.firstOrNull { it.key == l.running }?.name ?: ""}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
            Text(
                when {
                    l.stopping -> "Stopping after the game being played…"
                    p == null || !p.running -> "Starting the engine…"
                    else -> p.phase
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (p != null && p.total > 0) {
                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${p.done / 60} of ${p.total / 60} min",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (!l.stopping) OutlinedButton(onClick = { vm.stopLearning() }) { Text("Stop") }
        }
    }
}

@Composable
private fun LearnedDeck(name: String, st: LearnStatus, running: Boolean, onForget: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${st.games} games · " + when {
                            st.hasNet && st.hasPolicy -> "its own network and move policy"
                            st.hasNet -> "its own network"
                            st.hasPolicy -> "its own move policy"
                            else -> "nothing kept yet: its bots use the format's learning"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!running) TextButton(onClick = onForget) { Text("Forget") }
            }
            st.rounds.takeLast(5).reversed().forEach { r -> RoundLine(r) }
        }
    }
}

@Composable
private fun RoundLine(r: LearnRound) {
    val win = if (r.games > 0) "${(100.0 * r.deckWins / r.games).toInt()}% won" else "no games"
    val net = if (r.netStart > 0 && r.net > 0) "network ${"%.3f".format(r.netStart)} → ${"%.3f".format(r.net)}" else "network not trained"
    val test = if (r.arenaPairs > 0) "test ${"%.2f".format(r.arenaScore)} on ${r.arenaPairs} deals" else "no test"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (r.kept) "✓" else "·", modifier = Modifier.width(18.dp), fontWeight = FontWeight.Bold,
            color = if (r.kept) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Round ${r.round}: ${r.games} games, $win · $net · $test" + if (r.kept) " · kept" else "",
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}
