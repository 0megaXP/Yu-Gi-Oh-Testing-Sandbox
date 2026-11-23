@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ygosandbox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextOverflow
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
            var auto by remember { mutableStateOf(vm.autoLearnReplays) }
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Send new replays to the engine automatically", style = MaterialTheme.typography.bodyMedium)
                    Text("When the app opens and every few minutes while it runs (not during a duel).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.Switch(checked = auto, onCheckedChange = { auto = it; vm.autoLearnReplays = it })
            }
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
                            val data = ReplayLearning.database()
                            val known = data.ids
                            val fresh = fs.filter { (_, b) -> OmegaImport.id(b) !in known }
                            // lines the engine hasn't replayed yet (the app was closed during a run)
                            val waiting = data.pending().size + data.outdated.size
                            note = if (fresh.isEmpty() && waiting == 0) "No new replays from that period in the folder." else null
                            // all the period's files: they also give back decks older runs didn't keep
                            if (fresh.isNotEmpty() || waiting > 0) ReplayLearning.analyze(fs, vm.db, vm::engine)
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
            val waiting = remember(state.revision, state.running) { ReplayLearning.database().let { it.pending().size + it.outdated.size } }
            if (waiting > 0 && !state.running) Text(
                "$waiting game${if (waiting == 1) "" else "s"} or line${if (waiting == 1) "" else "s"} still to be read again or replayed by the engine: Analyze replays continues.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
            )
            Learned(vm, state.revision)
            EngineLearningShare(vm)
        }
    }
}

/**
 * Sending the replays to the engine's nightly learning on GitHub: a token that can write to the
 * engine repository, and a switch. Only training numbers leave the device.
 */
@Composable
private fun EngineLearningShare(vm: AppViewModel) {
    val share = com.ygosandbox.app.history.LearningShare
    val st by share.state.collectAsState()
    var token by remember { mutableStateOf(share.token) }
    var on by remember { mutableStateOf(share.enabled) }
    val scope = rememberCoroutineScope()
    val platform = remember { com.ygosandbox.app.platform.AppPlatform.current }
    Text("Engine learning", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Text(
        "The engine's value network trains every night on GitHub. With a token, your finished games go to it too: " +
            "the engine re-plays each one by the real rules (both players, every phase) and sends the positions it " +
            "passes through, as the network's numbers, with who won. No card names, " +
            "players or accounts are sent; the data is public on the engine repository. The token is a GitHub " +
            "fine-grained token for 0megaXP/Yu-Gi-Oh-Engine with Contents: read and write; it stays on this device.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Send my replays to the engine's learning", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = on, onCheckedChange = { on = it; share.enabled = it })
    }
    OutlinedTextField(
        value = token, onValueChange = { token = it }, singleLine = true, label = { Text("GitHub token") },
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !st.running && on && token.isNotBlank(), onClick = {
            share.token = token
            token = share.token
            scope.launch {
                val folder = platform.preferences("replays").getString("omega_folder", null) ?: platform.omegaReplays?.path
                if (folder == null) return@launch
                val files = runCatching { platform.readFolder(folder, ".bytes") { _, _ -> true } }.getOrDefault(emptyList())
                share.send(files, vm.db, vm::engine)
            }
        }) { Text("Save & send now") }
    }
    if (st.running) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text("${st.sent} game${if (st.sent == 1) "" else "s"} sent so far" + (st.note?.let { " · $it" } ?: ""),
        style = MaterialTheme.typography.bodySmall)
    remember(st.sent, st.running) { share.followSummary() }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What the replays taught, per archetype. */
@Composable
private fun Learned(vm: AppViewModel, revision: Int) {
    val cn = remember(vm.db.cards.size) { CardNames(vm.db) }
    val all = remember(revision, cn) { runCatching { ReplayKnowledge.all(ReplayLearning.database(), cn) }.getOrDefault(emptyList()) }
    val data = remember(revision) { ReplayLearning.database() }
    if (data.games.isEmpty()) return
    Text("${data.games.size} games, ${data.turns.size} turns · the engine verified ${data.turns.count { it.check?.verified == true }} lines",
        fontWeight = FontWeight.SemiBold)
    if (all.isEmpty()) return
    // one archetype at a time, picked from a dropdown (most played first)
    val sorted = remember(all) { all.sortedByDescending { it.games } }
    var picked by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf(false) }
    val k = sorted.firstOrNull { it.archetype == picked } ?: sorted.first()
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Archetype (${sorted.size})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${k.archetype} · ${k.games} game${if (k.games == 1) "" else "s"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sorted.forEach { a ->
                DropdownMenuItem(
                    text = { Text("${a.archetype} · ${a.games} game${if (a.games == 1) "" else "s"}") },
                    onClick = { picked = a.archetype; open = false },
                )
            }
        }
    }
    KnowledgeSummary(k)
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
            if (k.stopsWith.isNotEmpty()) Text(
                "Stops opponents with: " + k.stopsWith.take(4).joinToString(", ") { "${it.first} (${it.second}×)" },
                style = MaterialTheme.typography.bodySmall,
            )
            if (k.underTurns > 0) Text("Under Mulcharmy / Droll: ${k.underTurns} turn${if (k.underTurns == 1) "" else "s"}, " +
                "performance ${(k.underPerformance * 100).toInt()}/100", style = MaterialTheme.typography.bodySmall)
            k.bestTurns.firstOrNull()?.let { t ->
                Text("Best line (${if (t.goingFirst) "going first" else "going second"}, turn ${t.turn}, performance ${(t.performance * 100).toInt()}/100" +
                    (if (t.check?.verified == true) ", verified" else "") + "): " +
                    t.steps.filter { !it.interaction && (it.action == "activate" || it.action.endsWith("summon")) }.take(8).joinToString(" → ") { it.name },
                    style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
            if (k.turnsChecked > 0) Text("Engine: ${k.turnsVerified} of ${k.turnsChecked} first turns verified and learned",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (k.choices > 0) {
                var open by remember(k.archetype) { mutableStateOf(false) }
                Text("Choices in its lines: the engine understands ${k.understood} of ${k.choices}" +
                    if (k.notUnderstood.isNotEmpty()) "; ${k.notUnderstood.size} it doesn't yet (its bots follow the players there)" else "",
                    style = MaterialTheme.typography.bodySmall)
                k.example?.let { c ->
                    Text("Example: ${c.action.substringBefore(" — ")} → ${c.chosen}. ${c.why}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (open) 20 else 3)
                }
                if (k.notUnderstood.isNotEmpty()) {
                    TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Choices not understood yet") }
                    if (open) k.notUnderstood.take(8).forEach { c ->
                        Text("• ${c.action.substringBefore(" — ")} → ${c.chosen}: ${c.why}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
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
