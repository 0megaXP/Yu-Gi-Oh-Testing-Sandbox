@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ygosandbox.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ygosandbox.app.AppViewModel
import com.ygosandbox.app.UiState
import com.ygosandbox.app.combos.OmegaDecks
import com.ygosandbox.app.model.GameFormat
import com.ygosandbox.app.model.SourceDeck
import com.ygosandbox.app.platform.AppPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Import decks from YGO Omega: every deck in its Player.data (found by itself on a computer, or
 * picked), .ydk files, or a pasted Omega deck code. The person ticks which ones to add.
 */
@Composable
fun OmegaDeckImportDialog(ui: UiState, vm: AppViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val platform = remember { AppPlatform.current }
    val found = remember { mutableStateListOf<SourceDeck>() }
    val picked = remember { mutableStateListOf<Int>() }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var codeName by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(GameFormat.TCG) }

    fun sameCards(a: SourceDeck, main: List<Long>, extra: List<Long>, side: List<Long>): Boolean {
        fun key(l: List<Long>) = l.map { vm.db.find(it)?.id ?: it }.sorted()
        return key(a.main) == key(main) && key(a.extra) == key(extra) && key(a.side) == key(side)
    }
    fun alreadyMine(d: SourceDeck) = ui.myDecks.any { it.name == d.name && sameCards(d, it.main, it.extra, it.side) }
    fun add(decks: List<SourceDeck>) {
        decks.forEach { d ->
            if (found.any { it.name == d.name && sameCards(it, d.main, d.extra, d.side) }) return@forEach
            found += d
            if (!alreadyMine(d)) picked += found.lastIndex
        }
    }
    fun read(files: List<Pair<String, ByteArray>>) {
        scope.launch {
            busy = true
            val problems = ArrayList<String>()
            val decks = withContext(Dispatchers.Default) {
                files.flatMap { (name, bytes) ->
                    runCatching { OmegaDecks.readFile(name, bytes, vm.db) }
                        .onFailure { problems += "$name: ${it.message ?: "couldn't be read"}" }.getOrDefault(emptyList())
                }
            }
            add(decks)
            busy = false
            note = when {
                decks.isEmpty() && problems.isNotEmpty() -> problems.joinToString("\n")
                decks.isEmpty() -> "No decks in those files."
                else -> "${decks.size} deck${if (decks.size == 1) "" else "s"} found" +
                    (if (problems.isNotEmpty()) " (" + problems.joinToString("; ") + ")" else "") + "."
            }
        }
    }

    // On a computer, Omega's Player.data is read at once when it is where Omega installs.
    LaunchedEffect(Unit) {
        val f = platform.omegaDecks ?: return@LaunchedEffect
        val bytes = withContext(Dispatchers.IO) { runCatching { f.readBytes() }.getOrNull() } ?: return@LaunchedEffect
        read(listOf(f.name to bytes))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import from YGO Omega") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Omega keeps every saved deck in Player.data, in its install folder under YGO Omega_Data\\Files " +
                        "(older copies are in Files\\Backup). Choose it, or .ydk files, or paste a deck code.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                platform.omegaDecks?.let {
                    Text("Read from ${it.path}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(enabled = !busy, onClick = {
                    scope.launch {
                        val files = platform.pickFiles("Player.data or .ydk decks", platform.omegaDecks?.parentFile,
                            extensions = listOf("data", "bak", "ydk", "txt"))
                        if (files.isNotEmpty()) read(files)
                    }
                }) { Text("Choose Player.data or .ydk files") }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(code, { code = it }, label = { Text("Omega deck code") }, singleLine = true,
                        modifier = Modifier.weight(1f))
                }
                if (code.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(codeName, { codeName = it }, label = { Text("Name") }, singleLine = true,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val d = OmegaDecks.readCode(codeName.trim(), code, vm.db)
                        if (d == null) note = "That isn't an Omega deck code."
                        else { add(listOf(d)); code = ""; codeName = ""; note = null }
                    }) { Text("Add") }
                }
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (found.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${picked.size} of ${found.size} ticked", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            if (picked.size == found.size) picked.clear()
                            else { picked.clear(); picked.addAll(found.indices) }
                        }) { Text(if (picked.size == found.size) "None" else "All") }
                    }
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        itemsIndexed(found) { i, d ->
                            Row(
                                Modifier.fillMaxWidth().clickable { if (i in picked) picked.remove(i) else picked.add(i) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = i in picked, onCheckedChange = null)
                                Column(Modifier.weight(1f)) {
                                    Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${d.main.size} main · ${d.extra.size} extra · ${d.side.size} side" +
                                            if (alreadyMine(d)) " · already in My decks" else "",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GameFormat.entries.forEach { f ->
                            FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = picked.isNotEmpty() && !busy, onClick = {
                val chosen = picked.sorted().map { found[it] }
                chosen.forEach { vm.importDeck(it, format) }
                vm.showMessage("Imported ${chosen.size} deck${if (chosen.size == 1) "" else "s"} from YGO Omega")
                onDismiss()
            }) { Text(if (picked.isEmpty()) "Import" else "Import ${picked.size}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
