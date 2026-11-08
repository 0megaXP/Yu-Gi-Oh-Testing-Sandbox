@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ygosandbox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ygosandbox.app.AppViewModel
import com.ygosandbox.app.UiState
import com.ygosandbox.app.data.CardDatabase
import com.ygosandbox.app.model.GameFormat

/** Browse the card database by name, or list every card of an archetype. */
@Composable
fun CardsScreen(ui: UiState, vm: AppViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var byArchetype by rememberSaveable { mutableStateOf(false) }
    var pagerStart by remember { mutableStateOf<Int?>(null) }

    val results = remember(query, byArchetype, ui.dbRevision) {
        val q = query.trim()
        when {
            q.length < 2 -> emptyList()
            byArchetype -> vm.db.membersOf(q).let { ids -> vm.db.cards.filter { it.id in ids } }.sortedBy { it.group.ordinal }
            else -> vm.db.cards.filter { it.name.contains(q, ignoreCase = true) }
                .sortedBy { if (it.name.startsWith(q, ignoreCase = true)) 0 else 1 }
        }.take(500)
    }
    val archetypeHint = remember(query, byArchetype, ui.archetypes) {
        if (!byArchetype || query.trim().length < 2) emptyList()
        else ui.archetypes.filter { it.contains(query.trim(), ignoreCase = true) && !it.equals(query.trim(), ignoreCase = true) }.take(6)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header("Card database", onBack = { vm.back() })
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text(if (byArchetype) "Archetype" else "Card name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    FilterChip(selected = !byArchetype, onClick = { byArchetype = false }, label = { Text("By name") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = byArchetype, onClick = { byArchetype = true }, label = { Text("By archetype") })
                }
                archetypeHint.forEach { a ->
                    Text(
                        a, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().clickable { query = a }.padding(vertical = 6.dp),
                    )
                }
                Text(
                    if (query.trim().length < 2) "${ui.cardCount} cards · type at least 2 letters"
                    else "${results.size}${if (results.size == 500) "+" else ""} cards",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                itemsIndexed(results, key = { _, c -> c.id }) { i, c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { pagerStart = i }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = c.imageUrl(small = true), contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(40.dp).height(58.dp).clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text(
                                typeLine(c) + (if (c.archetype.isNotEmpty()) " · ${c.archetype}" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        pagerStart?.let { start ->
            val items = remember(results) { results.map { PagerItem(it.id, it.name, it) } }
            CardPager(items, start, GameFormat.TCG, onClose = { pagerStart = null })
        }
    }
}
