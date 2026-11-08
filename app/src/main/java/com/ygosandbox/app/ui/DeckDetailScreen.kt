@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ygosandbox.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.ygosandbox.app.model.GameFormat
import com.ygosandbox.app.model.TimeSpan
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ygosandbox.app.AppViewModel
import com.ygosandbox.app.UiState
import com.ygosandbox.app.data.Banlists
import com.ygosandbox.app.model.CardGroup
import com.ygosandbox.app.model.CardStat
import com.ygosandbox.app.model.DeckAnalysis
import com.ygosandbox.app.model.SourceDeck
import com.ygosandbox.app.model.Usage
import java.time.LocalDate

@Composable
fun DeckDetailScreen(ui: UiState, vm: AppViewModel, id: String) {
    val deck = ui.decks.firstOrNull { it.id == id }
    if (deck == null) {
        Column { Header("Deck", onBack = { vm.back() }) }
        return
    }
    var tab by rememberSaveable(id) { mutableIntStateOf(0) }
    var pagerStart by remember(id) { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var viewing by remember(id) { mutableStateOf<SourceDeck?>(null) }

    val groupOf: (CardStat) -> CardGroup = { s -> vm.db.find(s.cardId)?.group ?: CardGroup.UNKNOWN }
    val engine = remember(deck, ui.dbRevision) { ordered(deck.cards.filter { it.engine }, groupOf) }
    val nonEngine = remember(deck, ui.dbRevision) { ordered(deck.cards.filter { !it.engine }, groupOf) }
    val shown = if (tab == 0) engine else nonEngine

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header(deck.title, onBack = { vm.back() }) {
                IconButton(
                    onClick = { vm.startSearch(deck.archetypes, deck.excluded, deck.format, deck.span, replaceId = deck.id) },
                    enabled = ui.searching == null,
                ) { Icon(Icons.Default.Refresh, "Search again") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
            }
            ui.searching?.let { SearchProgress(it, onCancel = { vm.cancelSearch() }) }
            Summary(deck)
            EditSearchPanel(deck, ui, vm)
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Engine (${engine.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Non-engine (${nonEngine.size})") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Decks (${deck.decks.size})") })
            }
            if (tab == 2) SourceDeckList(deck.decks, onOpen = { viewing = it })
            else CardSections(shown, vm, ui.dbRevision) { index -> pagerStart = index }
        }

        pagerStart?.let { start ->
            val items = remember(shown, ui.dbRevision) {
                shown.map { PagerItem(it.cardId, it.name, vm.db.find(it.cardId), it) }
            }
            CardPager(items, start, deck.format, onClose = { pagerStart = null })
        }
        viewing?.let { d -> SourceDeckViewer(d, deck.format, vm, ui.dbRevision, ui.deckGrid, onClose = { viewing = null }) }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${deck.title}?") },
        text = { Text("This removes the saved analysis from your deck list.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteDeck(deck.id) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

/** Openable panel to change the archetypes (and format/time span) and run the search again. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EditSearchPanel(deck: DeckAnalysis, ui: UiState, vm: AppViewModel) {
    var open by rememberSaveable(deck.id) { mutableStateOf(false) }
    val wanted = remember(deck.id) { androidx.compose.runtime.mutableStateListOf(*deck.archetypes.toTypedArray()) }
    val ignored = remember(deck.id) { androidx.compose.runtime.mutableStateListOf(*deck.excluded.toTypedArray()) }
    var format by remember(deck.id) { mutableStateOf(deck.format) }
    var span by remember(deck.id) { mutableStateOf(deck.span) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Tune, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Edit search", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (open) "Close" else "Open")
            }
            if (open) Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionTitle("Archetypes")
                ArchetypePicker(
                    help = "Decks must play all of these.",
                    field = "Add an archetype",
                    tags = wanted, taken = ignored, all = ui.archetypes,
                )
                SectionTitle("Ignore")
                ArchetypePicker(
                    help = "Skip decks that play any of these.",
                    field = "Archetype to ignore",
                    tags = ignored, taken = wanted, all = ui.archetypes,
                )
                SectionTitle("Format and time span")
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GameFormat.entries.forEach { f ->
                        FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label) })
                    }
                    TimeSpan.entries.forEach { t ->
                        FilterChip(selected = span == t, onClick = { span = t }, label = { Text(t.label) })
                    }
                }
                Button(
                    onClick = {
                        vm.startSearch(wanted.toList(), ignored.toList(), format, span, replaceId = deck.id)
                    },
                    enabled = wanted.isNotEmpty() && ui.searching == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Search again with these") }
            }
        }
    }
}

/** Main, Side, Mix, Niche; inside each by card type (Monster, Spell, Trap, Fusion…), then most played. */
private fun ordered(list: List<CardStat>, groupOf: (CardStat) -> CardGroup) =
    list.sortedWith(
        compareBy<CardStat> { it.usage.ordinal }.thenBy { groupOf(it).ordinal }
            .thenByDescending { it.anyRate }.thenBy { it.name },
    )

@Composable
private fun Summary(d: DeckAnalysis) {
    val since = d.since.takeIf { it.isNotEmpty() }?.let { "since ${Banlists.pretty(LocalDate.parse(it))}" } ?: "all time"
    val older = d.decks.count { !it.currentFormat }
    Text(
        (if (d.excluded.isNotEmpty()) d.excludedLabel.replaceFirstChar { it.uppercase() } + " · " else "") +
        "${d.format.label} · ${d.span.label} ($since) · ${d.decks.size} matching decks of ${d.decksScanned} checked" +
            (if (older > 0) " · $older from older formats" else ""),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun CardSections(cards: List<CardStat>, vm: AppViewModel, dbRevision: Int, onOpen: (Int) -> Unit) {
    if (cards.isEmpty()) {
        Text(
            "No cards in this category.",
            modifier = Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val indexed = cards.withIndex().toList()
    val groups = indexed.groupBy { it.value.usage }
    // Sections that are closed; all start open.
    val closed = remember { androidx.compose.runtime.mutableStateListOf<Usage>() }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        Usage.entries.forEach { usage ->
            val rows = groups[usage].orEmpty()
            if (rows.isEmpty()) return@forEach
            val isOpen = usage !in closed
            item(key = "h_${usage.name}") {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { if (isOpen) closed += usage else closed.remove(usage) }
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${usage.label} · ${rows.size}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(usage.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(
                        if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        if (isOpen) "Close" else "Open",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (!isOpen) return@forEach
            rows.groupBy { vm.db.find(it.value.cardId)?.group ?: CardGroup.UNKNOWN }.forEach { (group, typed) ->
                item(key = "g_${usage.name}_${group.name}") {
                    Text(
                        "${group.label} · ${typed.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
                    )
                }
                items(typed, key = { "c_${it.value.cardId}" }) { (index, stat) ->
                    CardRow(stat, vm, dbRevision) { onOpen(index) }
                }
            }
        }
    }
}

@Composable
private fun CardRow(s: CardStat, vm: AppViewModel, dbRevision: Int, onClick: () -> Unit) {
    val card = remember(s.cardId, dbRevision) { vm.db.find(s.cardId) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = card?.imageUrl(small = true) ?: "https://images.ygoprodeck.com/images/cards_small/${s.cardId}.jpg",
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(40.dp).height(58.dp).clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (card != null) {
                    Box(Modifier.size(width = 4.dp, height = 14.dp).background(frameColor(card.frameType), RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(6.dp))
                }
                Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            }
            Text(
                statLine(s),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(pct(s.anyRate), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** Result groups for the Decks tab, best first. */
private enum class Placing(val label: String) {
    WIN("Winner"), SECOND("2nd place"), TOP4("Top 4"), TOP8("Top 8"), TOP16("Top 16"),
    TOP32("Top 32"), TOP64("Top 64"), LOWER("Lower or unknown placing"),
}

private val TOP_N = Regex("top\\s*(\\d+)", RegexOption.IGNORE_CASE)
private val RANK = Regex("(\\d+)\\s*(st|nd|rd|th)", RegexOption.IGNORE_CASE)

/** Reads "1st Place", "Top 8", "3rd-4th", "Winner"… into a group. */
private fun placingOf(text: String): Placing {
    val t = text.trim()
    val n = when {
        t.isEmpty() -> null
        t.contains("winner", ignoreCase = true) || t.contains("champion", ignoreCase = true) -> 1
        else -> TOP_N.find(t)?.groupValues?.get(1)?.toIntOrNull()
            ?: RANK.find(t)?.groupValues?.get(1)?.toIntOrNull()
            ?: t.toIntOrNull()
    } ?: return Placing.LOWER
    return when {
        n <= 1 -> Placing.WIN
        n == 2 -> Placing.SECOND
        n <= 4 -> Placing.TOP4
        n <= 8 -> Placing.TOP8
        n <= 16 -> Placing.TOP16
        n <= 32 -> Placing.TOP32
        n <= 64 -> Placing.TOP64
        else -> Placing.LOWER
    }
}

@Composable
private fun SourceDeckList(decks: List<SourceDeck>, onOpen: (SourceDeck) -> Unit) {
    val uri = LocalUriHandler.current
    val groups = remember(decks) { decks.groupBy { placingOf(it.placement) }.toSortedMap() }
    // Group names that are closed; all groups start open.
    val closed = remember { androidx.compose.runtime.mutableStateListOf<String>() }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groups.forEach { (placing, list) ->
            val isOpen = placing.name !in closed
            item(key = "p_${placing.name}") {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { if (isOpen) closed += placing.name else closed.remove(placing.name) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${placing.label} · ${list.size}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        if (isOpen) "Close" else "Open",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (isOpen) itemsIndexed(list, key = { i, _ -> "d_${placing.name}_$i" }) { _, d ->
                SourceDeckCard(d) {
                    if (d.hasList) onOpen(d) else if (d.url.isNotEmpty()) runCatching { uri.openUri(d.url) }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SourceDeckCard(d: SourceDeck, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(enabled = d.hasList || d.url.isNotEmpty(), onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(d.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val event = listOf(d.placement, d.tournament).filter { it.isNotBlank() }.joinToString(" · ")
            if (event.isNotEmpty()) Text(event, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d.date.isNotEmpty()) Text(
                    Banlists.pretty(LocalDate.parse(d.date)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    d.formatLabel,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (d.currentFormat) FontWeight.Normal else FontWeight.SemiBold,
                    color = if (d.currentFormat) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
