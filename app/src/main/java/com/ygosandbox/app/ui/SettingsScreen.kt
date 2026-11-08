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
import com.ygosandbox.app.BuildConfig
import com.ygosandbox.app.DbStatus
import com.ygosandbox.app.UiState
import com.ygosandbox.app.data.Banlists
import com.ygosandbox.app.model.GameFormat
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(ui: UiState, vm: AppViewModel) {
    var ghToken by rememberSaveable { mutableStateOf(vm.settings.githubToken) }
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
                        "Installed: ${BuildConfig.VERSION_NAME}" + (if (update != null) " · Available: ${update.version}" else ""),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Updates come from your private GitHub repository, so the app needs a read-only token " +
                            "with access to Yu-Gi-Oh-Testing-Sandbox. It stays on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = ghToken, onValueChange = { ghToken = it },
                        label = { Text("GitHub token") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val cleaned = ghToken.filterNot { it.isWhitespace() }
                            ghToken = cleaned
                            vm.settings.githubToken = cleaned
                            vm.checkForUpdates(manual = true)
                        }) { Text("Save & check") }
                        if (update != null) Button(
                            onClick = { vm.installUpdate() },
                            enabled = ui.updateProgress == null,
                        ) { Text("Update to ${update.version}") }
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
