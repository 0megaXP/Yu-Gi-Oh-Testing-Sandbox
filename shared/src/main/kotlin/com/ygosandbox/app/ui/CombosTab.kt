@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ygosandbox.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ygosandbox.app.AppViewModel
import com.ygosandbox.app.combos.ComboLine
import com.ygosandbox.app.combos.ComboViewModel
import com.ygosandbox.app.combos.Lines
import com.ygosandbox.app.combos.VideoCombo
import com.ygosandbox.app.data.Banlists
import com.ygosandbox.app.model.DeckAnalysis
import java.time.LocalDate

private enum class Period(val label: String) { BANLIST("Since the banlist"), MONTHS("Last 3 months"), ANY("Any time") }

/**
 * Combo lines read from YouTube videos of this archetype. A video only counts if every card its
 * combo uses is played by at least one tournament list in this result.
 */
@Composable
fun CombosTab(deck: DeckAnalysis, vm: AppViewModel, cvm: ComboViewModel, openCards: (List<Long>, Int) -> Unit) {
    val ui by cvm.ui.collectAsState()
    LaunchedEffect(deck.id) { cvm.library(deck.id) }
    val lib = ui.libraries[deck.id]
    val lines = remember(lib) { lib?.let { Lines.group(it.videos) } ?: emptyList() }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(cvm.settings.apiKey.isNotBlank()) }
    var free by remember { mutableStateOf(cvm.settings.freeReading) }
    var showDropped by rememberSaveable(deck.id) { mutableStateOf(false) }
    val busyHere = ui.runFor == deck.id && ui.run != null
    val busyElsewhere = ui.run != null && ui.runFor != deck.id

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { InterruptionNote() }
        if ((!free && !hasKey) || showSettings) item {
            KeyPanel(cvm, onDone = { showSettings = false; hasKey = cvm.settings.apiKey.isNotBlank() })
        }
        if (busyHere) item { RunPanel(ui.run!!, onStop = { cvm.stop() }) }
        ui.runNote?.takeIf { ui.runFor == deck.id }?.let { note -> item { ComboNote(note) } }

        item {
            if (ui.searchFor == deck.id && (ui.searching || ui.hits.isNotEmpty() || ui.searchError != null)) {
                PickVideos(deck, vm, cvm, readUrls = lib?.readUrls ?: emptySet())
            } else {
                FindPanel(deck, vm, cvm, free = free, onFree = { cvm.settings.freeReading = it; free = it },
                    enabled = !busyHere && !busyElsewhere && (free || hasKey),
                    onSettings = { showSettings = !showSettings })
            }
        }
        if (busyElsewhere) item { ComboNote("Another deck's videos are being read; wait for that to finish.") }

        if (lines.isNotEmpty()) item {
            val n = lib?.videos?.size ?: 0
            val verified = lib?.videos?.count { it.check?.verified == true } ?: 0
            val unchecked = lib?.videos?.count { it.check == null } ?: 0
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${lines.size} line${if (lines.size == 1) "" else "s"} from $n video${if (n == 1) "" else "s"}",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                ComboNote(
                    "$verified verified by the engine. Verified lines are replayed by the real rules; their " +
                        "sub-combos steer the engine's search, the combo tree and the bots for decks that can play them."
                )
                if (unchecked > 0 && !busyHere && !busyElsewhere) OutlinedButton(onClick = { cvm.check(deck, vm.db, vm::engine) }) {
                    Text("Check $unchecked with the engine")
                }
            }
        }
        items(lines, key = { it.main.url }) { line -> LineCard(vm, cvm, deck.id, line, openCards) }

        if (lines.isEmpty() && lib != null && !busyHere && ui.searchFor != deck.id) item {
            ComboNote(
                "No combo lines yet. Find combo videos to read them here. Videos whose combos use a card " +
                    "that none of this result's ${deck.decks.size} tournament lists play are left out."
            )
        }

        if (!lib?.dropped.isNullOrEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().clickable { showDropped = !showDropped }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Left out (${lib!!.dropped.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(if (showDropped) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
            }
            if (showDropped) items(lib!!.dropped, key = { "d" + it.url }) { d ->
                val uri = LocalUriHandler.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { if (d.url.startsWith("http")) runCatching { uri.openUri(d.url) } }) {
                        Text(d.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        ComboNote(d.reason)
                    }
                    IconButton(onClick = { cvm.forget(deck.id, d.url) }) { Icon(Icons.Default.Close, "Forget, so it can be read again") }
                }
            }
        }
    }
}

@Composable
internal fun KeyPanel(cvm: ComboViewModel, onDone: () -> Unit) {
    var key by remember { mutableStateOf(cvm.settings.apiKey) }
    var model by remember { mutableStateOf(cvm.settings.model) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Reading videos", fontWeight = FontWeight.SemiBold)
            ComboNote(
                "The app downloads each video, keeps one frame per screen change and has Claude read the cards " +
                    "and steps. That needs an Anthropic API key (console.anthropic.com); it is kept on this phone only. " +
                    "A 10-minute video costs roughly what 200 images do."
            )
            OutlinedTextField(key, { key = it }, label = { Text("API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { cvm.settings.apiKey = key; cvm.settings.model = model; onDone() }, enabled = key.isNotBlank()) { Text("Save") }
                if (cvm.settings.apiKey.isNotBlank()) TextButton(onClick = onDone) { Text("Cancel") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FindPanel(
    deck: DeckAnalysis,
    vm: AppViewModel,
    cvm: ComboViewModel,
    free: Boolean,
    onFree: (Boolean) -> Unit,
    enabled: Boolean,
    onSettings: () -> Unit,
) {
    var period by rememberSaveable { mutableStateOf(Period.BANLIST) }
    val banlist = remember(deck.format) { Banlists.latest(deck.format, vm.settings.customBanlists(deck.format.name)) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Period.entries.forEach { p ->
                val label = if (p == Period.BANLIST && banlist != null) "Since ${Banlists.pretty(banlist)}" else p.label
                FilterChip(selected = period == p, onClick = { period = p }, label = { Text(label) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = free, onClick = { onFree(true) }, label = { Text("Free reading") })
            FilterChip(selected = !free, onClick = { onFree(false) }, label = { Text("Claude reading") })
        }
        ComboNote(
            if (free) "Free: card names are read on the phone and the engine works out the steps and the end board. " +
                "Best with EDOPro or Master Duel videos."
            else "Claude reads every action, the hand and the end board, also from commentary and real tables. Uses your API key (paid)."
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    val since = when (period) {
                        Period.BANLIST -> (banlist ?: LocalDate.now().minusDays(90)).toString()
                        Period.MONTHS -> LocalDate.now().minusMonths(3).toString()
                        Period.ANY -> null
                    }
                    cvm.search(deck, since)
                },
                enabled = enabled,
            ) { Text("Find combo videos") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "API key and model") }
        }
    }
}

@Composable
private fun PickVideos(deck: DeckAnalysis, vm: AppViewModel, cvm: ComboViewModel, readUrls: Set<String>) {
    val ui by cvm.ui.collectAsState()
    // the first five not read before are ticked
    var picked by remember(ui.hits) { mutableStateOf(ui.hits.filter { it.url !in readUrls }.take(5).map { it.url }.toSet()) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Combo videos for ${deck.title}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = { cvm.clearSearch() }) { Icon(Icons.Default.Close, "Close") }
            }
            if (ui.searching) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                ComboNote("Searching YouTube…")
            }
            ui.searchError?.let { ComboNote(it, error = true) }
            ui.hits.forEach { h ->
                val done = h.url in readUrls
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = !done) {
                    picked = if (h.url in picked) picked - h.url else picked + h.url
                }) {
                    Checkbox(checked = h.url in picked, onCheckedChange = null, enabled = !done)
                    Column(Modifier.weight(1f)) {
                        Text(h.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        ComboNote(listOfNotNull(h.uploader.ifBlank { null }, h.date.ifBlank { null },
                            ComboViewModel.clock(h.seconds.toDouble()), if (done) "already read" else null).joinToString(" · "))
                    }
                }
            }
            if (ui.hits.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { cvm.read(deck, vm.db, ui.hits.filter { it.url in picked }, vm::engine) },
                    enabled = picked.isNotEmpty(),
                ) { Text("Read ${picked.size} video${if (picked.size == 1) "" else "s"}") }
                ComboNote("Each video is downloaded, read and deleted; only the combo is kept.")
            }
        }
    }
}

@Composable
private fun RunPanel(p: com.ygosandbox.app.combos.RunProgress, onStop: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Video ${p.video} of ${p.videos}: ${p.stage}", fontWeight = FontWeight.SemiBold)
                    Text(p.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onStop) { Text("Stop") }
            }
            LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
            ComboNote("Keep the app open while it reads.")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineCard(vm: AppViewModel, cvm: ComboViewModel, deckId: String, line: ComboLine, openCards: (List<Long>, Int) -> Unit) {
    val v = line.main
    val uri = LocalUriHandler.current
    var showSteps by rememberSaveable("steps" + v.url) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Seen in ${line.videos.size} video${if (line.videos.size == 1) "" else "s"} · ${v.steps.size} steps" +
                    if (v.going == "second") " · going second" else "",
                fontWeight = FontWeight.SemiBold,
            )
            CheckBadge(line)
            val board = v.endBoard.filter { it.onField }.map { it.code }
            ComboLabel("End board")
            Thumbs(vm, board, openCards)
            if (v.hand.isNotEmpty()) {
                ComboLabel("Opening hand")
                Thumbs(vm, v.hand, openCards)
            }
            if (v.interruptions.isNotEmpty()) {
                ComboLabel("Interruptions")
                v.interruptions.forEach { ComboNote("• $it") }
            }
            if (v.chokePoints.isNotEmpty()) {
                ComboLabel("Choke points")
                v.chokePoints.forEach { ComboNote("• $it") }
            }
            Row(Modifier.fillMaxWidth().clickable { showSteps = !showSteps }, verticalAlignment = Alignment.CenterVertically) {
                ComboLabel("Steps", Modifier.weight(1f))
                Icon(if (showSteps) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (showSteps) {
                val marks = v.check?.stepMatch.orEmpty()
                if (marks.isNotEmpty()) ComboNote("✓ played by the rules · ⚠ couldn't be played there")
                v.steps.forEachIndexed { i, s ->
                    val mark = when (marks.getOrNull(i)) { 1 -> "✓ "; 0 -> "⚠ "; else -> "" }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.code?.let { code -> CardThumbnail(vm, code, width = 28) { openCards(listOf(code), 0) } }
                            ?: Spacer(Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text("$mark${i + 1}. ${s.action.replace('_', ' ').replaceFirstChar { it.uppercase() }}: ${s.name}",
                                style = MaterialTheme.typography.bodyMedium)
                            if (s.detail.isNotBlank()) ComboNote(s.detail)
                        }
                        s.t?.let { t ->
                            TextButton(onClick = { runCatching { uri.openUri(at(v.url, t)) } }) { Text(ComboViewModel.clock(t)) }
                        }
                    }
                }
                if (v.uncertain.isNotEmpty()) {
                    ComboLabel("Not sure about")
                    v.uncertain.forEach { ComboNote("• $it") }
                }
            }
            ComboLabel("Videos")
            line.videos.forEach { src -> Source(src, onOpen = { if (src.url.startsWith("http")) runCatching { uri.openUri(src.url) } }, onForget = { cvm.forget(deckId, src.url) }) }
        }
    }
}

/** What the engine made of the line: the best check among its videos. */
@Composable
private fun CheckBadge(line: ComboLine) {
    val checks = line.videos.mapNotNull { it.check }
    val best = checks.firstOrNull { it.verified } ?: checks.maxByOrNull { it.verifiedSteps * 1000 + it.stepsMatched }
    val text = when {
        best == null -> "Not checked by the engine yet"
        best.verified -> "✓ Played by the engine: ${best.stepsMatched} of ${best.stepsChecked} steps, " +
            "${(best.boardMatch * 100).toInt()}% of the end board"
        best.partly -> "◐ Partly played by the engine: the first ${best.verifiedSteps} of ${best.stepsChecked} steps follow " +
            "the rules, ${best.stepsMatched} in all. The video's order breaks after that (often a chain clicked out of order); " +
            "the played start still teaches the engine"
        else -> "Not verified: ${best.problem}"
    }
    Text(
        text, style = MaterialTheme.typography.bodySmall,
        color = when {
            best?.verified == true -> MaterialTheme.colorScheme.tertiary
            best?.partly == true -> MaterialTheme.colorScheme.secondary
            best == null -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.error
        },
    )
}

@Composable
private fun Source(v: VideoCombo, onOpen: () -> Unit, onForget: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
            Text(v.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ComboNote(listOf(v.uploader, v.date).filter { it.isNotBlank() }.joinToString(" · "))
        }
        IconButton(onClick = onForget) { Icon(Icons.Default.Close, "Remove this video") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Thumbs(vm: AppViewModel, codes: List<Long>, openCards: (List<Long>, Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        codes.forEachIndexed { i, c -> CardThumbnail(vm, c, width = 40) { openCards(codes, i) } }
    }
}

@Composable
private fun ComboLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

@Composable
internal fun ComboNote(text: String, error: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The video link at second [t]. */
private fun at(url: String, t: Double) = url + (if ('?' in url) "&" else "?") + "t=${t.toInt()}s"
