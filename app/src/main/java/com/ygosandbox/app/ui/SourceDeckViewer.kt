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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.ygosandbox.app.data.Banlists
import com.ygosandbox.app.model.Card
import com.ygosandbox.app.model.CardGroup
import com.ygosandbox.app.model.GameFormat
import com.ygosandbox.app.model.SourceDeck
import java.time.LocalDate

private data class ListEntry(val id: Long, val card: Card?, val copies: Int)

/** A downloaded tournament deck list, with Import to My decks. */
@Composable
fun SourceDeckViewer(d: SourceDeck, format: GameFormat, vm: AppViewModel, dbRevision: Int, grid: Boolean, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val uri = LocalUriHandler.current
    var pager by remember { mutableStateOf<Int?>(null) }
    var imported by remember(d) { mutableStateOf<String?>(null) }

    val parts = remember(d, dbRevision) {
        listOf("Main Deck" to d.main, "Extra Deck" to d.extra, "Side Deck" to d.side).map { (label, ids) ->
            val entries = ids.map { vm.db.find(it)?.id ?: it }.groupingBy { it }.eachCount()
                .map { (id, n) -> ListEntry(id, vm.db.find(id), n) }
                .sortedWith(compareBy<ListEntry> { (it.card?.group ?: CardGroup.UNKNOWN).ordinal }.thenBy { it.card?.name ?: "" })
            Triple(label, ids.size, entries)
        }
    }
    val pagerItems = remember(parts) {
        parts.flatMap { it.third }.distinctBy { it.id }
            .map { PagerItem(it.id, it.card?.name ?: "Unknown card #${it.id}", it.card) }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
                Text(d.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                GridToggle(grid, vm::setDeckGrid)
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                item(key = "info") {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val event = listOf(d.placement, d.tournament).filter { it.isNotBlank() }.joinToString(" · ")
                        if (event.isNotEmpty()) Text(event, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOfNotNull(
                                d.date.takeIf { it.isNotEmpty() }?.let { Banlists.pretty(LocalDate.parse(it)) },
                                d.formatLabel,
                                "${d.main.size} / ${d.extra.size} / ${d.side.size}",
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (d.currentFormat) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { imported = vm.importDeck(d, format) }) {
                                Icon(Icons.Default.Download, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Import to My decks")
                            }
                            if (d.url.isNotEmpty()) OutlinedButton(onClick = { runCatching { uri.openUri(d.url) } }) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
                                Spacer(Modifier.width(6.dp))
                                Text("YGOPRODeck")
                            }
                        }
                        imported?.let {
                            Text(
                                "Saved in My decks as \"$it\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
                parts.forEach { (label, count, entries) ->
                    if (entries.isEmpty()) return@forEach
                    item(key = "h_$label") {
                        Text(
                            "$label · $count",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    if (grid) {
                        cardGrid("grid_$label", entries.flatMap { e -> List(e.copies) { e.id to e.card } }) { id ->
                            pager = pagerItems.indexOfFirst { it.cardId == id }.coerceAtLeast(0)
                        }
                        return@forEach
                    }
                    entries.groupBy { it.card?.group ?: CardGroup.UNKNOWN }.forEach { (group, rows) ->
                        item(key = "g_${label}_${group.name}") {
                            Text(
                                "${group.label} · ${rows.sumOf { it.copies }}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
                            )
                        }
                        items(rows, key = { "c_${label}_${it.id}" }) { e ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { pager = pagerItems.indexOfFirst { it.cardId == e.id }.coerceAtLeast(0) }
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AsyncImage(
                                    model = e.card?.imageUrl(small = true) ?: "https://images.ygoprodeck.com/images/cards_small/${e.id}.jpg",
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(36.dp).height(52.dp).clip(RoundedCornerShape(3.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    e.card?.name ?: "Unknown card #${e.id}",
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("×${e.copies}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
    pager?.let { start -> Box(Modifier.fillMaxSize()) { CardPager(pagerItems, start, format, onClose = { pager = null }) } }
}
