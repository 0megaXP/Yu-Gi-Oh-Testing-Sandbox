@file:OptIn(ExperimentalLayoutApi::class)

package com.ygosandbox.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ygosandbox.app.AppViewModel
import com.ygosandbox.app.combos.Agreed
import com.ygosandbox.app.combos.ComboViewModel
import com.ygosandbox.app.combos.GuideSummary
import com.ygosandbox.app.combos.GuideVideo
import com.ygosandbox.app.combos.GuideViewModel
import com.ygosandbox.app.model.DeckAnalysis

/**
 * Analysis: what YouTube guides (deck profiles, how-to-play videos) teach about this archetype,
 * read from their captions and merged. The bots use it in duels; explained combos go to the
 * Combos tab, where the engine checks them.
 */
@Composable
fun GuideTab(deck: DeckAnalysis, vm: AppViewModel, gvm: GuideViewModel, cvm: ComboViewModel, openCards: (List<Long>, Int) -> Unit) {
    val ui by gvm.ui.collectAsState()
    LaunchedEffect(deck.id) { gvm.library(deck.id) }
    val lib = ui.libraries[deck.id]
    val summary = remember(lib) { lib?.let { GuideSummary.of(it) } }
    var hasKey by remember { mutableStateOf(gvm.settings.apiKey.isNotBlank()) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    val running = ui.stage.isNotEmpty()
    val busyHere = running && ui.runFor == deck.id
    val busyElsewhere = running && ui.runFor != deck.id
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            ComboNote(
                "Finds the best YouTube guides for this archetype (deck profiles, how-to-play and combo explanations), " +
                    "reads what they say from their captions with Claude, and keeps it. In duels the bot playing this deck " +
                    "opens with the starters, searches in the guides' order and keeps the key cards; the other bot " +
                    "saves its interruptions for the choke points. Explained combos go to the Combos tab, where the " +
                    "engine checks them by the real rules."
            )
        }
        if (!hasKey || showKey) item { KeyPanel(cvm, onDone = { showKey = false; hasKey = gvm.settings.apiKey.isNotBlank() }) }
        if (busyHere) item {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(ui.stage, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(progress = { ui.fraction }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { gvm.stop() }) { Text("Stop") }
                }
            }
        }
        ui.note?.takeIf { ui.runFor == deck.id }?.let { item { ComboNote(it) } }
        if (busyElsewhere) item { ComboNote("Another archetype's guides are being read; wait for that to finish.") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val read = lib?.videos?.count { it.status == "read" } ?: 0
                Button(enabled = hasKey && !running, onClick = {
                    gvm.findAndRead(deck, vm.db, 10) { lines ->
                        cvm.add(deck.id, lines)
                        cvm.check(deck, vm.db, vm::engine)
                    }
                }) { Text(if (read == 0) "Find and read the 10 best guides" else "Read 10 more guides") }
                if (hasKey) TextButton(onClick = { showKey = !showKey }) { Text("API key") }
            }
        }

        if (summary == null) {
            if (lib != null && lib.videos.isNotEmpty() && !busyHere) item { ComboNote("None of the videos read so far had a usable guide.") }
        } else {
            item {
                Text(
                    "What ${summary.videos} guide${if (summary.videos == 1) "" else "s"} say",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                )
            }
            if (summary.strategies.isNotEmpty()) item { Strategies(summary.strategies) }
            item { CardList(vm, "Starters", "tried first on the bot's own turn", summary.starters, summary.videos, openCards) }
            item { CardList(vm, "Extenders", "keep the combo going", summary.extenders, summary.videos, openCards) }
            item { CardList(vm, "Search first", "best first; the bot searches in this order", summary.searchFirst, summary.videos, openCards) }
            item { CardList(vm, "Keep", "not spent as cost or material by the bot", summary.protect, summary.videos, openCards) }
            item { CardList(vm, "Choke points", "the other bot saves its interruptions for these", summary.chokePoints, summary.videos, openCards) }
            item { CardList(vm, "Weak to", "opponent cards the guides warn about", summary.weakTo, summary.videos, openCards) }
            item { CardList(vm, "Deck building", "card choices and ratios explained", summary.buildNotes, summary.videos, openCards) }
            if (summary.tips.isNotEmpty()) item { Tips(summary.tips) }
            val combos = lib!!.readings.sumOf { it.second.combos.size }
            if (combos > 0) item {
                ComboNote("$combos combo${if (combos == 1) "" else "s"} explained in these guides. The ones whose cards are all in " +
                    "the tournament lists are in the Combos tab, checked by the engine; verified ones steer the bots.")
            }
        }

        if (!lib?.videos.isNullOrEmpty()) {
            item { Text("Videos", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp)) }
            items(lib!!.videos.sortedByDescending { it.readAt }, key = { it.url }) { v -> VideoRow(v) }
            item {
                TextButton(enabled = !running, onClick = { confirmClear = true }) { Text("Forget these guides") }
                if (confirmClear) androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmClear = false },
                    title = { Text("Forget the guides?") },
                    text = { Text("What was read from these videos is deleted; the bots stop using it. Combos already in the Combos tab stay.") },
                    confirmButton = { TextButton(onClick = { gvm.clear(deck.id); confirmClear = false }) { Text("Forget") } },
                    dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
                )
            }
        }
    }
}

@Composable
private fun Strategies(list: List<Pair<String, String>>) {
    var all by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Game plan", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            (if (all) list else list.take(2)).forEach { (text, title) ->
                Column {
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                    Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (list.size > 2) TextButton(onClick = { all = !all }) { Text(if (all) "Show less" else "Show all ${list.size}") }
        }
    }
}

@Composable
private fun CardList(
    vm: AppViewModel,
    title: String,
    hint: String,
    cards: List<Agreed>,
    videos: Int,
    openCards: (List<Long>, Int) -> Unit,
) {
    if (cards.isEmpty()) return
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (open) "Hide notes" else "Show notes")
            }
            val shown = cards.take(12)
            val codes = shown.map { it.card.code }.filter { it != 0L }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                shown.forEach { a ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (a.card.code != 0L) CardThumbnail(vm, a.card.code, width = 46) { openCards(codes, codes.indexOf(a.card.code)) }
                        Text("${a.videos}/$videos", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (open) shown.forEach { a ->
                Column {
                    Text("${a.card.name} · named by ${a.videos} of $videos", style = MaterialTheme.typography.labelLarge)
                    a.notes.take(3).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun Tips(tips: List<Pair<String, String>>) {
    var all by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Tips", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            (if (all) tips else tips.take(6)).forEach { (tip, _) -> Text("• $tip", style = MaterialTheme.typography.bodySmall) }
            if (tips.size > 6) TextButton(onClick = { all = !all }) { Text(if (all) "Show less" else "Show all ${tips.size}") }
        }
    }
}

@Composable
private fun VideoRow(v: GuideVideo) {
    val uri = LocalUriHandler.current
    Row(Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(v.url) } }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(v.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(v.uploader, v.date).filter { it.isNotBlank() }.joinToString(" · ") + " · " + when (v.status) {
                    "read" -> "read"
                    "no_captions" -> "no captions, skipped"
                    else -> "couldn't read: ${v.error}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (v.status == "read") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
