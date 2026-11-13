@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ygosandbox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ygosandbox.app.AppViewModel
import com.ygosandbox.app.combos.ArchetypeKnowledge
import com.ygosandbox.app.combos.CardNames
import com.ygosandbox.app.combos.MatchReplay
import com.ygosandbox.app.combos.OmegaImport
import com.ygosandbox.app.combos.ReplayKnowledge
import com.ygosandbox.app.combos.ReplayLearning
import com.ygosandbox.app.model.DeckAnalysis
import kotlinx.coroutines.launch

private fun pct(w: Int, l: Int) = if (w + l == 0) "–" else "$w–$l"

/**
 * Settings: YGO Omega's replay folder, analysed for every deck. Each new replay adds both
 * players' turns to the engine's replay database; the engine replays the recorded player's first
 * turns, keeps their combo pieces and learns its move policy from them.
 */
@Composable
fun ReplayLearningCard(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    val platform = remember { com.ygosandbox.app.platform.AppPlatform.current }
    // same keys the Replays tab used, so a folder chosen there carries over
    val prefs = remember { platform.preferences("replays") }
    var folder by remember { mutableStateOf(prefs.getString("omega_folder", null) ?: platform.omegaReplays?.path ?: "") }
    var days by remember { mutableStateOf(prefs.getInt("omega_days", 14)) }
    var note by remember { mutableStateOf<String?>(null) }
    var looking by remember { mutableStateOf(false) }
    val state by ReplayLearning.state.collectAsState()
    fun setFolder(f: String) {
        folder = f
        prefs.edit().putString("omega_folder", f).apply()
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("YGO Omega replays", style = MaterialTheme.typography.titleMedium)
            Text(
                "Every replay is analysed for every deck, both players and every turn: the lines played, going first or " +
                    "second, under Mulcharmy or Droll, and how they did. The engine replays the recorded player's first " +
                    "turns, keeps their combo pieces and learns from them, so it improves replay after replay. Omega keeps " +
                    "replays on Windows in %USERPROFILE%\\AppData\\LocalLow\\Duelists Unite\\YGO Omega.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (platform.canTypeFolder) {
                OutlinedTextField(value = folder, onValueChange = { setFolder(it) }, singleLine = true,
                    label = { Text("Replay folder") }, modifier = Modifier.fillMaxWidth())
            } else {
                Text(if (folder.isBlank()) "No folder chosen yet." else "Folder: " +
                    java.net.URLDecoder.decode(folder.substringAfterLast("/tree/"), "UTF-8"),
                    style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(14 to "Last 2 weeks", 90 to "Last 3 months", 0 to "All").forEach { (d, label) ->
                    FilterChip(selected = days == d, onClick = { days = d; prefs.edit().putInt("omega_days", d).apply() }, label = { Text(label) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !state.running && !looking && folder.isNotBlank(), onClick = {
                    scope.launch {
                        looking = true
                        note = "Looking through the folder…"
                        val since = if (days == 0) 0L else System.currentTimeMillis() - days * 86_400_000L
                        val files = runCatching {
                            platform.readFolder(folder, ".bytes") { name, modified -> OmegaImport.savedAt(name, modified) >= since }
                        }
                        looking = false
                        files.onSuccess { fs ->
                            val known = ReplayLearning.database().ids
                            val fresh = fs.filter { (_, b) -> OmegaImport.id(b) !in known }
                            note = if (fresh.isEmpty()) "No new replays from that period in the folder." else null
                            if (fresh.isNotEmpty()) ReplayLearning.analyze(fresh, vm.db, vm::engine)
                        }.onFailure { e -> note = "Couldn't read the folder: ${e.message ?: e.javaClass.simpleName}" }
                    }
                }) { Text("Analyze replays") }
                OutlinedButton(enabled = !state.running && !looking, onClick = {
                    scope.launch { platform.pickFolder("YGO Omega replay folder", folder.ifBlank { null })?.let { setFolder(it) } }
                }) { Text(if (folder.isBlank()) "Choose folder" else "Change folder") }
                TextButton(enabled = !state.running && !looking, onClick = {
                    scope.launch {
                        note = null
                        val fs = platform.pickFiles("YGO Omega replays", folder.takeIf { platform.canTypeFolder && it.isNotBlank() }?.let { java.io.File(it) })
                        if (fs.isNotEmpty()) ReplayLearning.analyze(fs, vm.db, vm::engine)
                    }
                }) { Text("Pick files") }
            }
            if (state.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.progress, style = MaterialTheme.typography.bodySmall)
            }
            (note ?: state.note)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Learned(vm, state.revision)
        }
    }
}

/** What the replays taught, per archetype. */
@Composable
private fun Learned(vm: AppViewModel, revision: Int) {
    val cn = remember(vm.db.cards.size) { CardNames(vm.db) }
    val all = remember(revision, cn) { runCatching { ReplayKnowledge.all(ReplayLearning.database(), cn) }.getOrDefault(emptyList()) }
    val data = remember(revision) { ReplayLearning.database() }
    if (data.games.isEmpty()) return
    var showAll by remember { mutableStateOf(false) }
    Text("${data.games.size} games, ${data.turns.size} turns · the engine verified ${data.turns.count { it.check?.verified == true }} lines",
        fontWeight = FontWeight.SemiBold)
    (if (showAll) all else all.take(5)).forEach { k -> KnowledgeSummary(k) }
    if (all.size > 5) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show fewer" else "All ${all.size} decks") }
}

@Composable
fun KnowledgeSummary(k: ArchetypeKnowledge) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(10.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${k.archetype} · ${k.games} game${if (k.games == 1) "" else "s"}", fontWeight = FontWeight.SemiBold)
            Text("Going first ${pct(k.winsFirst, k.lossesFirst)} · going second ${pct(k.winsSecond, k.lossesSecond)} · " +
                "%.1f activations opening, %.1f per turn".format(k.playsFirst, k.plays), style = MaterialTheme.typography.bodySmall)
            if (k.chokes.isNotEmpty()) Text(
                "Stopped most on: " + k.chokes.take(4).joinToString(", ") { "${it.name} (${it.times}×" + (if (it.lostAfter > 0) ", lost ${it.lostAfter}" else "") + ")" },
                style = MaterialTheme.typography.bodySmall,
            )
            if (k.underTurns > 0) Text("Under Mulcharmy / Droll: ${k.underTurns} turn${if (k.underTurns == 1) "" else "s"}, " +
                "performance ${(k.underPerformance * 100).toInt()}/100", style = MaterialTheme.typography.bodySmall)
            k.bestTurns.firstOrNull()?.let { t ->
                Text("Best line (${if (t.goingFirst) "going first" else "going second"}, turn ${t.turn}, performance ${(t.performance * 100).toInt()}/100" +
                    (if (t.check?.verified == true) ", verified" else "") + "): " +
                    t.steps.filter { it.action == "activate" || it.action.endsWith("summon") }.take(8).joinToString(" → ") { it.name },
                    style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
            if (k.turnsChecked > 0) Text("Engine: ${k.turnsVerified} of ${k.turnsChecked} first turns verified and learned",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Replays tab: what the replay analysis knows about this deck's archetype. */
@Composable
fun ArchetypeLearned(deck: DeckAnalysis, vm: AppViewModel) {
    val state by ReplayLearning.state.collectAsState()
    val cn = remember(vm.db.cards.size) { CardNames(vm.db) }
    val k = remember(state.revision, deck.id, cn) { runCatching { ReplayKnowledge.of(ReplayLearning.database(), deck.title, cn) }.getOrNull() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ReplayNote("YGO Omega replays are analysed for every deck from Settings (set the replay folder there).")
        k?.let { KnowledgeSummary(it) }
    }
}

/** This deck's YGO Omega games from the replay analysis. */
@Composable
fun rememberOmegaMatches(deck: DeckAnalysis, vm: AppViewModel): List<MatchReplay> {
    val state by ReplayLearning.state.collectAsState()
    val cn = remember(vm.db.cards.size) { CardNames(vm.db) }
    return remember(state.revision, deck.id, cn) {
        runCatching { ReplayKnowledge.matches(ReplayLearning.database(), deck.title, cn) }.getOrDefault(emptyList())
    }
}
