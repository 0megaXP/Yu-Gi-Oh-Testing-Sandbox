@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ygosandbox.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ygosandbox.app.AppViewModel
import com.ygosandbox.app.DbStatus
import com.ygosandbox.app.Screen
import com.ygosandbox.app.UiState
import com.ygosandbox.app.data.Banlists
import com.ygosandbox.app.model.DeckAnalysis
import com.ygosandbox.app.model.GameFormat
import com.ygosandbox.app.model.TimeSpan
import com.ygosandbox.app.model.Usage
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date

// ---------------- Saved decks ----------------

@Composable
fun DecksScreen(ui: UiState, vm: AppViewModel) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header("Archetype search", onBack = { vm.back() })
            ui.searching?.let { SearchProgress(it, onCancel = { vm.cancelSearch() }) }
            if (ui.decks.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No decks yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Search one or more archetypes to see which cards tournament decks play with them.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(ui.decks, key = { it.id }) { d ->
                        DeckRow(d) { vm.open(Screen.DeckDetail(d.id)) }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { vm.open(Screen.NewSearch) },
            icon = { Icon(Icons.Default.Search, null) },
            text = { Text("New search") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }
}

@Composable
private fun DeckRow(d: DeckAnalysis, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(d.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (d.excluded.isNotEmpty()) Text(
                d.excludedLabel, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "${d.format.label} · ${d.span.label} · ${d.decks.size} decks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val engineMain = d.cards.count { it.engine && it.usage == Usage.MAIN }
            val nonMain = d.cards.count { !it.engine && it.usage == Usage.MAIN }
            Text(
                "$engineMain engine and $nonMain non-engine staples · searched " +
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(d.createdAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SearchProgress(text: String, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = onCancel) { Text("Cancel") }
            }
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

// ---------------- New search ----------------

@Composable
fun NewSearchScreen(ui: UiState, vm: AppViewModel) {
    val selected = remember { mutableStateListOf<String>() }
    val excluded = remember { mutableStateListOf<String>() }
    var format by rememberSaveable { mutableStateOf(GameFormat.TCG) }
    var span by rememberSaveable { mutableStateOf(TimeSpan.RECENT) }
    val ready = ui.dbStatus == DbStatus.READY

    Column(Modifier.fillMaxSize()) {
        Header("New deck search", onBack = { vm.back() })
        ui.searching?.let { SearchProgress(it, onCancel = { vm.cancelSearch() }) }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Archetypes")
            ArchetypePicker(
                help = "Decks must play all of these. Decks with extra archetypes are included.",
                field = if (ready) "Add an archetype" else "Card database still loading…",
                tags = selected, taken = excluded, all = ui.archetypes,
            )

            SectionTitle("Ignore")
            ArchetypePicker(
                help = "Skip decks that play any of these (2 or more of their cards).",
                field = "Archetype to ignore (optional)",
                tags = excluded, taken = selected, all = ui.archetypes,
            )

            SectionTitle("Format")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GameFormat.entries.forEach { f ->
                    FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label) })
                }
            }

            SectionTitle("Time span")
            val dates = remember(format, ui.dbRevision) { vm.banlistDates(format) }
            val latest = dates.lastOrNull()
            TimeSpan.entries.forEach { t ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = span == t, onClick = { span = t }, role = Role.RadioButton)
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = span == t, onClick = null)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(t.label)
                        Text(
                            spanDetail(t, latest),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { vm.startSearch(selected.toList(), excluded.toList(), format, span) },
                enabled = selected.isNotEmpty() && ui.searching == null && ui.cardCount > 0,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (ui.searching != null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Search decks")
            }
            Text(
                "Uses tournament decks from YGOPRODeck. A deck counts when it runs at least 2 cards of each archetype.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Tag input with suggestions from the archetype list. [taken] are tags used by the other list. */
@Composable
fun ArchetypePicker(
    help: String,
    field: String,
    tags: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
    taken: List<String>,
    all: List<String>,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val suggestions = remember(query, all, tags.size, taken.size) {
        val q = query.trim()
        if (q.isEmpty()) emptyList()
        else {
            val pool = all.filter { a -> (tags + taken).none { it.equals(a, ignoreCase = true) } }
            (pool.filter { it.startsWith(q, ignoreCase = true) } + pool.filter { it.contains(q, ignoreCase = true) })
                .distinct().take(8)
        }
    }

    fun add(a: String) {
        val v = a.trim()
        if (v.isNotEmpty() && (tags + taken).none { it.equals(v, ignoreCase = true) }) tags += v
        query = ""
    }

    Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (tags.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.toList().forEach { a ->
                InputChip(
                    selected = true,
                    onClick = { tags.remove(a) },
                    label = { Text(a) },
                    trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(InputChipDefaults.IconSize)) },
                )
            }
        }
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(field) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            add(suggestions.firstOrNull { it.equals(query.trim(), ignoreCase = true) } ?: suggestions.firstOrNull() ?: query)
        }),
        trailingIcon = {
            if (query.isNotBlank()) IconButton(onClick = { add(query) }) { Icon(Icons.Default.Add, "Add as typed") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (suggestions.isNotEmpty()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
            Column {
                suggestions.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        s,
                        modifier = Modifier.fillMaxWidth().clickable { add(s) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun spanDetail(t: TimeSpan, latest: LocalDate?): String {
    val today = LocalDate.now()
    return when (t) {
        TimeSpan.RECENT -> {
            val twoWeeks = today.minusDays(14)
            if (latest != null && latest.isAfter(twoWeeks)) "Since the new banlist of ${Banlists.pretty(latest)} (less than 2 weeks ago)"
            else "Since ${Banlists.pretty(twoWeeks)} (last 2 weeks)"
        }
        TimeSpan.THIS_BANLIST ->
            if (latest != null) "Since the banlist of ${Banlists.pretty(latest)}" else "No banlist date known; uses the last 90 days"
        TimeSpan.ALL_TIME -> "No date limit; decks from older formats are marked"
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
fun SmallOutlinedButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) { Text(text) }
}
