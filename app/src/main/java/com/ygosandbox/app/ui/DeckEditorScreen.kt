@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ygosandbox.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ygosandbox.app.AppViewModel
import com.ygosandbox.app.UiState
import com.ygosandbox.app.model.Card
import com.ygosandbox.app.model.CardGroup
import com.ygosandbox.app.model.DeckPart
import com.ygosandbox.app.model.GameFormat
import com.ygosandbox.app.model.SavedDeck
import com.ygosandbox.app.model.banLimit

/** One distinct card in a part of the deck, with its number of copies. */
private data class Entry(val cardId: Long, val card: Card?, val copies: Int)

@Composable
fun DeckEditorScreen(ui: UiState, vm: AppViewModel, id: String) {
    val deck = ui.myDecks.firstOrNull { it.id == id }
    if (deck == null) {
        Column { Header("Deck", onBack = { vm.back() }) }
        return
    }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var pager by remember { mutableStateOf<Pair<List<PagerItem>, Int>?>(null) }
    val closed = remember { mutableStateListOf<DeckPart>() }

    val parts = remember(deck, ui.dbRevision) {
        DeckPart.entries.associateWith { p -> entries(deck.part(p), vm) }
    }
    val problems = remember(deck, ui.dbRevision) { problemsOf(deck, vm) }
    // Every distinct card in display order, for the card viewer's arrows.
    val allItems = remember(parts) {
        DeckPart.entries.flatMap { p -> parts[p].orEmpty() }.distinctBy { it.cardId }
            .map { PagerItem(it.cardId, it.card?.name ?: "Unknown card #${it.cardId}", it.card) }
    }

    fun showCard(cardId: Long) {
        val i = allItems.indexOfFirst { it.cardId == cardId }.coerceAtLeast(0)
        pager = allItems to i
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header(deck.name, onBack = { vm.back() }) {
                GridToggle(ui.deckGrid, vm::setDeckGrid)
                IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Rename") }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, "Delete") }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                item(key = "top") {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GameFormat.entries.forEach { f ->
                                FilterChip(
                                    selected = deck.format == f,
                                    onClick = { vm.setDeckFormat(deck.id, f) },
                                    label = { Text(f.label) },
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${deck.main.size} / ${deck.extra.size} / ${deck.side.size}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (problems.isNotEmpty()) Problems(problems)
                    }
                }
                DeckPart.entries.forEach { part ->
                    val list = parts[part].orEmpty()
                    val count = deck.part(part).size
                    val isOpen = part !in closed
                    item(key = "h_${part.name}") {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { if (isOpen) closed += part else closed.remove(part) }
                                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${part.label} · $count",
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
                    if (!isOpen) return@forEach
                    if (list.isEmpty()) item(key = "e_${part.name}") {
                        Text(
                            if (part == DeckPart.SIDE) "No Side Deck cards" else "No cards yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    if (ui.deckGrid) {
                        cardGrid("grid_${part.name}", list.flatMap { e -> List(e.copies) { e.cardId to e.card } }) { showCard(it) }
                        return@forEach
                    }
                    list.groupBy { it.card?.group ?: CardGroup.UNKNOWN }.forEach { (group, rows) ->
                        item(key = "g_${part.name}_${group.name}") {
                            Text(
                                "${group.label} · ${rows.sumOf { it.copies }}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
                            )
                        }
                        items(rows, key = { "c_${part.name}_${it.cardId}" }) { e ->
                            DeckCardRow(
                                e, part, deck.format,
                                onOpen = { showCard(e.cardId) },
                                onAdd = {
                                    vm.addCard(deck.id, e.cardId, toSide = part == DeckPart.SIDE)?.let(vm::showMessage)
                                },
                                onRemove = { vm.removeCard(deck.id, e.cardId, part) },
                                onMove = { vm.moveCard(deck.id, e.cardId, part)?.let(vm::showMessage) },
                            )
                        }
                    }
                }
            }
        }
        androidx.compose.material3.ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("Add cards") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )

        if (adding) AddCardsPanel(ui, vm, deck, onClose = { adding = false }, onShow = { items, i -> pager = items to i })
        pager?.let { (items, start) ->
            CardPager(items, start, deck.format, onClose = { pager = null }) { item ->
                EditActions(deck, item.cardId, vm)
            }
        }
    }

    if (renaming) NameDialog(
        title = "Rename deck", initial = deck.name, confirm = "Rename",
        onDismiss = { renaming = false },
        onConfirm = { name, _ -> renaming = false; vm.renameDeck(deck.id, name) },
    )
    if (deleting) DeleteDeckDialog(deck.name, onDismiss = { deleting = false }, onConfirm = { deleting = false; vm.deleteMyDeck(deck.id) })
}

private fun entries(ids: List<Long>, vm: AppViewModel): List<Entry> =
    ids.groupingBy { it }.eachCount().map { (id, n) -> Entry(id, vm.db.find(id), n) }
        .sortedWith(compareBy<Entry> { (it.card?.group ?: CardGroup.UNKNOWN).ordinal }.thenBy { it.card?.name ?: "" })

/** Deck size and banlist issues, worded for the user. */
private fun problemsOf(deck: SavedDeck, vm: AppViewModel): List<String> = buildList {
    if (deck.main.size < DeckPart.MAIN.min) add("Main Deck has ${deck.main.size} cards; it needs at least 40")
    if (deck.main.size > DeckPart.MAIN.max) add("Main Deck has ${deck.main.size} cards; the most is 60")
    if (deck.extra.size > DeckPart.EXTRA.max) add("Extra Deck has ${deck.extra.size} cards; the most is 15")
    if (deck.side.size > DeckPart.SIDE.max) add("Side Deck has ${deck.side.size} cards; the most is 15")
    (deck.main + deck.extra + deck.side).distinct().forEach { id ->
        val card = vm.db.find(id) ?: return@forEach
        val status = if (deck.format == GameFormat.TCG) card.banTcg else card.banOcg
        val n = deck.copiesOf(id)
        val limit = banLimit(status)
        if (n > limit) add(
            if (limit == 0) "${card.name} is Forbidden in ${deck.format.label}"
            else "${card.name} is $status in ${deck.format.label}: $n copies, $limit allowed",
        )
    }
}

@Composable
private fun Problems(problems: List<String>) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(6.dp))
                Text("Not legal yet", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            problems.forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun DeckCardRow(
    e: Entry,
    part: DeckPart,
    format: GameFormat,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onMove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardThumb(e.card, e.cardId)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.card?.name ?: "Unknown card #${e.cardId}",
                maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
            )
            val status = e.card?.let { if (format == GameFormat.TCG) it.banTcg else it.banOcg }.orEmpty()
            if (status.isNotEmpty()) Text(
                status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
            )
        }
        Text("×${e.copies}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = onRemove) { Icon(Icons.Default.Remove, "Remove one") }
        IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add one") }
        IconButton(onClick = onMove) {
            Icon(Icons.Default.SwapVert, if (part == DeckPart.SIDE) "Move to Main/Extra" else "Move to Side")
        }
    }
}

/** Add / remove / move buttons under a card in the card viewer. */
@Composable
private fun EditActions(deck: SavedDeck, cardId: Long, vm: AppViewModel) {
    val inMain = deck.main.count { it == cardId } + deck.extra.count { it == cardId }
    val inSide = deck.side.count { it == cardId }
    val mainPart = if (deck.extra.contains(cardId) || vm.db.find(cardId)?.isExtraDeck == true) DeckPart.EXTRA else DeckPart.MAIN
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "In this deck: ${if (mainPart == DeckPart.EXTRA) "Extra" else "Main"} ×$inMain · Side ×$inSide",
            style = MaterialTheme.typography.labelLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.OutlinedButton(
                onClick = { vm.removeCard(deck.id, cardId, mainPart) }, enabled = inMain > 0,
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) { Text("− ${if (mainPart == DeckPart.EXTRA) "Extra" else "Main"}") }
            Button(
                onClick = { vm.addCard(deck.id, cardId, toSide = false)?.let(vm::showMessage) },
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) { Text("+ ${if (mainPart == DeckPart.EXTRA) "Extra" else "Main"}") }
            androidx.compose.material3.OutlinedButton(
                onClick = { vm.removeCard(deck.id, cardId, DeckPart.SIDE) }, enabled = inSide > 0,
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) { Text("− Side") }
            FilledTonalButton(
                onClick = { vm.addCard(deck.id, cardId, toSide = true)?.let(vm::showMessage) },
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) { Text("+ Side") }
        }
    }
}

@Composable
private fun CardThumb(card: Card?, id: Long) {
    AsyncImage(
        model = card?.imageUrl(small = true) ?: "https://images.ygoprodeck.com/images/cards_small/$id.jpg",
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.width(40.dp).height(58.dp).clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/** Full-screen search to add cards to the deck. */
@Composable
private fun AddCardsPanel(
    ui: UiState,
    vm: AppViewModel,
    deck: SavedDeck,
    onClose: () -> Unit,
    onShow: (List<PagerItem>, Int) -> Unit,
) {
    BackHandler(onBack = onClose)
    var query by rememberSaveable { mutableStateOf("") }
    var byArchetype by rememberSaveable { mutableStateOf(false) }
    val results = remember(query, byArchetype, ui.dbRevision) {
        val q = query.trim()
        when {
            q.length < 2 -> emptyList()
            byArchetype -> vm.db.membersOf(q).let { ids -> vm.db.cards.filter { it.id in ids } }
                .sortedWith(compareBy<Card> { it.group.ordinal }.thenBy { it.name })
            else -> vm.db.cards.filter { it.name.contains(q, ignoreCase = true) }
                .sortedBy { if (it.name.startsWith(q, ignoreCase = true)) 0 else 1 }
        }.take(300)
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Done") }
                Text("Add cards", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(
                    "${deck.main.size} / ${deck.extra.size} / ${deck.side.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text(if (byArchetype) "Archetype" else "Card name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !byArchetype, onClick = { byArchetype = false }, label = { Text("By name") })
                    FilterChip(selected = byArchetype, onClick = { byArchetype = true }, label = { Text("By archetype") })
                }
                if (query.trim().length < 2) Text(
                    "Type at least 2 letters. + adds to the Main Deck (Extra Deck monsters go to the Extra Deck); Side adds to the Side Deck.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                itemsIndexed(results, key = { _, c -> c.id }) { i, c ->
                    val copies = deck.copiesOf(c.id)
                    val status = if (deck.format == GameFormat.TCG) c.banTcg else c.banOcg
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onShow(results.map { PagerItem(it.id, it.name, it) }, i) }
                            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CardThumb(c, c.id)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text(
                                typeLine(c) + (if (status.isNotEmpty()) " · $status" else "") +
                                    (if (copies > 0) " · in deck ×$copies" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (copies > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Button(
                            onClick = { vm.addCard(deck.id, c.id, toSide = false)?.let(vm::showMessage) },
                            contentPadding = PaddingValues(horizontal = 10.dp),
                        ) { Text(if (c.isExtraDeck) "+ Extra" else "+ Main") }
                        Spacer(Modifier.width(6.dp))
                        FilledTonalButton(
                            onClick = { vm.addCard(deck.id, c.id, toSide = true)?.let(vm::showMessage) },
                            contentPadding = PaddingValues(horizontal = 10.dp),
                        ) { Text("Side") }
                    }
                }
            }
        }
    }
}
