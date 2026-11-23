@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ygosandbox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CardColors
import androidx.compose.material3.Switch
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ygosandbox.app.AppViewModel
import com.ygosandbox.app.DbStatus
import com.ygosandbox.app.UiState
import com.ygosandbox.app.data.Banlists
import com.ygosandbox.app.model.GameFormat
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(ui: UiState, vm: AppViewModel) {
    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

    Column(Modifier.fillMaxSize()) {
        Header("Settings", onBack = { vm.back() })
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- App version ----
            Card(colors = cardColors, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("App version", style = MaterialTheme.typography.titleMedium)
                    val update = ui.update
                    Text(
                        "Installed: ${com.ygosandbox.app.platform.AppPlatform.current.versionName}" + (if (update != null) " · Available: ${update.version}" else ""),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Updates come from the app's public GitHub releases; the app checks when it starts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.checkForUpdates(manual = true) }) { Text("Check for updates") }
                        if (update != null) Button(
                            onClick = { vm.installUpdate() },
                            enabled = ui.updateProgress == null,
                        ) { Text("Update to ${update.version}") }
                    }
                }
            }

            // ---- Engine (downloaded without updating the app) ----
            EngineCard(cardColors)

            // ---- Appearance ----
            Card(colors = cardColors, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeChoice.entries.forEach { t ->
                            FilterChip(selected = AppTheme.choice == t, onClick = { AppTheme.choice = t }, label = { Text(t.label) })
                        }
                    }
                }
            }

            // ---- Card database ----
            Card(colors = cardColors, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Card database", style = MaterialTheme.typography.titleMedium)
                    val at = vm.settings.cardDbDownloadedAt
                    Text(
                        when (ui.dbStatus) {
                            DbStatus.DOWNLOADING -> "Downloading…"
                            DbStatus.READY -> "${ui.cardCount} cards, ${ui.archetypes.size} archetypes" +
                                (if (at > 0) " · downloaded ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))}" else "")
                            DbStatus.LOADING -> "Opening…"
                            else -> "Not downloaded"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "From YGOPRODeck. The app checks once a day for a newer version and updates by itself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.updateCardDatabase() }, enabled = ui.dbStatus != DbStatus.DOWNLOADING) {
                            Text("Check now")
                        }
                        OutlinedButton(onClick = { vm.redownloadCardDatabase() }, enabled = ui.dbStatus != DbStatus.DOWNLOADING) {
                            Text("Download again")
                        }
                    }
                }
            }

            // ---- YGO Omega replays, analysed for every deck ----
            ReplayLearningCard(vm)

            // ---- Banlists ----
            BanlistCard(ui, vm)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(12.dp))
        }
    }
}

@Composable
private fun BanlistCard(ui: UiState, vm: AppViewModel) {
    var format by rememberSaveable { mutableStateOf(GameFormat.TCG) }
    var newDate by rememberSaveable { mutableStateOf("") }
    val custom = remember(format, ui.dbRevision) { vm.settings.customBanlists(format.name) }
    val builtIn = remember(format) { Banlists.builtIn(format) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Banlist dates", style = MaterialTheme.typography.titleMedium)
            Text(
                "Used for \"This banlist\", for \"Recent\" right after a new list, and to mark older formats. " +
                    "Add the date a new Forbidden & Limited list takes effect if it isn't here yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GameFormat.entries.forEach { f ->
                    FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label) })
                }
            }
            builtIn.reversed().forEach { d ->
                Text("${Banlists.pretty(d)}  (built in)", style = MaterialTheme.typography.bodyMedium)
            }
            custom.sortedDescending().forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.removeBanlistDate(format, d) }) {
                        Icon(Icons.Default.Close, "Remove", Modifier.size(18.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newDate, onValueChange = { newDate = it },
                    label = { Text("YYYY-MM-DD") }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { if (vm.addBanlistDate(format, newDate)) newDate = "" }) { Text("Add") }
            }
        }
    }
}

/** Which engine runs, and newer ones downloaded from the engine's releases. */
@Composable
private fun EngineCard(colors: CardColors) {
    val upd = com.ygosandbox.app.update.EngineUpdate
    val st by upd.state.collectAsState()
    val scope = rememberCoroutineScope()
    var auto by remember { mutableStateOf(upd.automatic) }
    val described = remember(st.ready, st.checking) { runCatching { upd.describe() }.getOrDefault("") }
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Engine", style = MaterialTheme.typography.titleMedium)
            Text(
                "The engine (duel rules, bots, the learned value network) updates on its own: newer engines are " +
                    "downloaded from its releases and used from the next start, without updating the app.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (described.isNotBlank()) Text(described, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Download engine updates automatically", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = auto, onCheckedChange = { auto = it; upd.automatic = it })
            }
            OutlinedButton(enabled = !st.checking, onClick = {
                scope.launch { upd.check(com.ygosandbox.app.platform.AppPlatform.current.engineLibAsset, manual = true) }
            }) { Text("Check for a newer engine") }
            if (st.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            st.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

