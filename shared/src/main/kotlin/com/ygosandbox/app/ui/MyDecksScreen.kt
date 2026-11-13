@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ygosandbox.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ygosandbox.app.AppViewModel
import com.ygosandbox.app.Screen
import com.ygosandbox.app.UiState
import com.ygosandbox.app.model.GameFormat
import com.ygosandbox.app.model.SavedDeck
import java.text.DateFormat
import java.util.Date

@Composable
fun MyDecksScreen(ui: UiState, vm: AppViewModel) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SavedDeck?>(null) }
    var deleting by remember { mutableStateOf<SavedDeck?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header("My decks", onBack = { vm.back() })
            if (ui.myDecks.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No decks yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Create a deck, then add cards from the card database.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(ui.myDecks, key = { it.id }) { d ->
                        MyDeckRow(
                            d,
                            onOpen = { vm.open(Screen.DeckEditor(d.id)) },
                            onRename = { renaming = d },
                            onDelete = { deleting = d },
                        )
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("New deck") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }

    if (creating) NameDialog(
        title = "New deck",
        initial = "",
        confirm = "Create",
        askFormat = true,
        onDismiss = { creating = false },
        onConfirm = { name, format -> creating = false; vm.createDeck(name, format) },
    )
    renaming?.let { d ->
        NameDialog(
            title = "Rename deck", initial = d.name, confirm = "Rename",
            onDismiss = { renaming = null },
            onConfirm = { name, _ -> renaming = null; vm.renameDeck(d.id, name) },
        )
    }
    deleting?.let { d ->
        DeleteDeckDialog(d.name, onDismiss = { deleting = null }, onConfirm = { deleting = null; vm.deleteMyDeck(d.id) })
    }
}

@Composable
private fun MyDeckRow(d: SavedDeck, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${d.format.label} · Main ${d.main.size} · Extra ${d.extra.size} · Side ${d.side.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Edited " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(d.updatedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRename) { Icon(Icons.Default.Edit, "Rename") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
        }
    }
}

/** Asks for a deck name (and, for a new deck, its format). */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    askFormat: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String, GameFormat) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    var format by remember { mutableStateOf(GameFormat.TCG) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Deck name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (askFormat) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameFormat.entries.forEach { f ->
                        FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, format) }, enabled = name.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DeleteDeckDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $name?") },
        text = { Text("The deck and its card list will be removed from this phone.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
