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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ygosandbox.app.AppViewModel
import com.ygosandbox.app.combos.ComboViewModel
import com.ygosandbox.app.combos.GameRecord
import com.ygosandbox.app.combos.MatchReplay
import com.ygosandbox.app.combos.ReplayViewModel
import com.ygosandbox.app.combos.TurnRecord
import com.ygosandbox.app.data.Banlists
import com.ygosandbox.app.model.DeckAnalysis
import java.time.LocalDate

/**
 * Whole matches of this archetype read from YouTube (feature matches, tournament rounds, ranked
 * replays): each game turn by turn for both players, who won and why, and what to take from it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReplaysTab(deck: DeckAnalysis, vm: AppViewModel, rvm: ReplayViewModel, cvm: ComboViewModel, openCards: (List<Long>, Int) -> Unit) {
    val ui by rvm.ui.collectAsState()
    LaunchedEffect(deck.id) { rvm.library(deck.id) }
    val lib = ui.libraries[deck.id]
    val busyHere = ui.runFor == deck.id && ui.run != null
    val busyElsewhere = ui.run != null && ui.runFor != deck.id
    val hasKey = rvm.settings.apiKey.isNotBlank()
    var free by remember { mutableStateOf(rvm.settings.freeReading) }
    var showDropped by rememberSaveable(deck.id) { mutableStateOf(false) }
    val omega = rememberOmegaMatches(deck, vm)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { InterruptionNote() }
        if (!free && !hasKey) item { ReplayNote("Add your Anthropic API key in the Combos tab (gear icon) for Claude reading, or use free reading.") }
        ui.run?.takeIf { busyHere }?.let { p ->
            item {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Match ${p.video} of ${p.videos}: ${p.stage}", fontWeight = FontWeight.SemiBold)
                                Text(p.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { rvm.stop() }) { Text("Stop") }
                        }
                        LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                        ReplayNote("Keep the app open while it reads. A match takes a few minutes.")
                    }
                }
            }
        }
        ui.runNote?.takeIf { ui.runFor == deck.id }?.let { n -> item { ReplayNote(n) } }

        item {
            if (ui.searchFor == deck.id && (ui.searching || ui.hits.isNotEmpty() || ui.searchError != null)) {
                PickMatches(deck, vm, rvm, lib?.readUrls ?: emptySet(), lib?.staleUrls ?: emptySet())
            } else {
                FindMatches(deck, vm, rvm, free, onFree = { rvm.settings.freeReading = it; free = it },
                    enabled = (free || hasKey) && !busyHere && !busyElsewhere)
            }
        }
        if (ui.searchFor != deck.id) item { ArchetypeLearned(deck, vm) }
        if (busyElsewhere) item { ReplayNote("Another deck's matches are being read; wait for that to finish.") }

        // YGO Omega games come from the replay analysis in Settings (all decks); videos from here
        val matches = (lib?.matches.orEmpty().filter { !it.format.startsWith("YGO Omega") } + omega).sortedByDescending { it.date }
        if (matches.isNotEmpty()) item { Record(deck, matches) }
        items(matches, key = { it.url }) { m -> MatchCard(vm, rvm, deck.id, m, openCards) }

        if (matches.isEmpty() && lib != null && !busyHere && ui.searchFor != deck.id) item {
            ReplayNote(
                "No matches yet. Find match videos to see how ${deck.title} games are won and lost, turn by turn. " +
                    "Matches where the ${deck.title} player uses a card none of this result's lists play are left out."
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        ReplayNote(d.reason)
                    }
                    IconButton(onClick = { rvm.forget(deck.id, d.url) }) { Icon(Icons.Default.Close, "Forget, so it can be read again") }
                }
            }
        }
    }
}

/**
 * YGO Omega replay files: read exactly (every activation, summon and result is in the file),
 * nothing to download or recognise. The person sets Omega's replay folder once; each run reads
 * the replays saved in the chosen period that weren't read before.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OmegaImportCard(deck: DeckAnalysis, vm: AppViewModel, rvm: ReplayViewModel, cvm: ComboViewModel, readUrls: Set<String>, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    val platform = remember { com.ygosandbox.app.platform.AppPlatform.current }
    val prefs = remember { platform.preferences("replays") }
    var folder by remember { mutableStateOf(prefs.getString("omega_folder", null) ?: platform.omegaReplays?.path ?: "") }
    var days by remember { mutableStateOf(prefs.getInt("omega_days", 14)) }
    var note by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    fun setFolder(f: String) {
        folder = f
        prefs.edit().putString("omega_folder", f).apply()
    }
    fun import(files: List<Pair<String, ByteArray>>) {
        if (files.isNotEmpty()) rvm.importOmega(deck, vm.db, files, cvm, vm::engine)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("YGO Omega replays", fontWeight = FontWeight.SemiBold)
            ReplayNote(
                "Read exactly from Omega's replay files, for free. Going first, the first turn is also added to the Combos tab " +
                    "for the engine to check. Omega keeps them on Windows in %USERPROFILE%\\AppData\\LocalLow\\Duelists Unite\\YGO Omega."
            )
            if (platform.canTypeFolder) {
                androidx.compose.material3.OutlinedTextField(
                    value = folder, onValueChange = { setFolder(it) }, singleLine = true,
                    label = { Text("Replay folder") }, modifier = Modifier.fillMaxWidth(),
                )
            } else {
                ReplayNote(if (folder.isBlank()) "No folder chosen yet." else "Folder: " +
                    java.net.URLDecoder.decode(folder.substringAfterLast("/tree/"), "UTF-8"))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(14 to "Last 2 weeks", 90 to "Last 3 months", 0 to "All").forEach { (d, label) ->
                    FilterChip(selected = days == d, onClick = { days = d; prefs.edit().putInt("omega_days", d).apply() }, label = { Text(label) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = enabled && !reading && folder.isNotBlank(), onClick = {
                    scope.launch {
                        reading = true
                        note = "Looking through the folder…"
                        val since = if (days == 0) 0L else System.currentTimeMillis() - days * 86_400_000L
                        val files = runCatching {
                            platform.readFolder(folder, ".bytes") { name, modified ->
                                com.ygosandbox.app.combos.OmegaImport.savedAt(name, modified) >= since
                            }
                        }
                        reading = false
                        files.onSuccess { fs ->
                            // every replay of the period is passed on, so a new game joins its Best of 3
                            val fresh = fs.count { (_, b) -> com.ygosandbox.app.combos.OmegaImport.id(b) !in readUrls }
                            note = if (fresh == 0) "No new replays from that period in the folder." else null
                            if (fresh > 0) import(fs)
                        }.onFailure { e -> note = "Couldn't read the folder: ${e.message ?: e.javaClass.simpleName}" }
                    }
                }) { Text("Analyze replays") }
                OutlinedButton(enabled = enabled && !reading, onClick = {
                    scope.launch { platform.pickFolder("YGO Omega replay folder", folder.ifBlank { null })?.let { setFolder(it) } }
                }) { Text(if (folder.isBlank()) "Choose folder" else "Change folder") }
                TextButton(enabled = enabled && !reading, onClick = {
                    scope.launch {
                        note = null
                        import(platform.pickFiles("YGO Omega replays", folder.takeIf { platform.canTypeFolder && it.isNotBlank() }?.let { java.io.File(it) }))
                    }
                }) { Text("Pick files") }
            }
            note?.let { ReplayNote(it) }
        }
    }
}

/** Wins and losses across the read matches, going first and second, and by opponent. */
@Composable
private fun Record(deck: DeckAnalysis, matches: List<MatchReplay>) {
    val games = matches.flatMap { m -> m.games.map { m.opponent to it } }
    fun wl(list: List<GameRecord>) = "${list.count { it.won == true }}–${list.count { it.won == false }}"
    val first = games.map { it.second }.filter { it.weFirst == true }
    val second = games.map { it.second }.filter { it.weFirst == false }
    val byOpp = games.groupBy { it.first }.mapValues { (_, v) -> v.map { it.second } }.entries.sortedByDescending { it.value.size }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${deck.title} in ${matches.size} match${if (matches.size == 1) "" else "es"}: ${wl(games.map { it.second })} in games",
                fontWeight = FontWeight.SemiBold)
            ReplayNote("Going first ${wl(first)} · going second ${wl(second)}")
            byOpp.take(5).forEach { (opp, g) -> ReplayNote("vs $opp: ${wl(g)}") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FindMatches(
    deck: DeckAnalysis,
    vm: AppViewModel,
    rvm: ReplayViewModel,
    free: Boolean,
    onFree: (Boolean) -> Unit,
    enabled: Boolean,
) {
    var sinceBanlist by rememberSaveable { mutableStateOf(true) }
    val banlist = remember(deck.format) { Banlists.latest(deck.format, vm.settings.customBanlists(deck.format.name)) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = sinceBanlist, onClick = { sinceBanlist = true },
                label = { Text(banlist?.let { "Since ${Banlists.pretty(it)}" } ?: "Last 90 days") })
            FilterChip(selected = !sinceBanlist, onClick = { sinceBanlist = false }, label = { Text("Last 6 months") })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = free, onClick = { onFree(true) }, label = { Text("Free reading") })
            FilterChip(selected = !free, onClick = { onFree(false) }, label = { Text("Claude reading") })
        }
        ReplayNote(
            if (free) "Free: on the phone. Videos showing a duel log (Duelingbook and the like) give turns, games and " +
                "results; other videos give a timeline of the cards shown."
            else "Claude follows both players through every game, from the screen and the commentary. Uses your API key (paid)."
        )
        Button(
            onClick = {
                val since = if (sinceBanlist) (banlist ?: LocalDate.now().minusDays(90)).toString() else LocalDate.now().minusMonths(6).toString()
                rvm.search(deck, since)
            },
            enabled = enabled,
        ) { Text("Find match videos") }
    }
}

@Composable
private fun PickMatches(deck: DeckAnalysis, vm: AppViewModel, rvm: ReplayViewModel, readUrls: Set<String>, stale: Set<String> = emptySet()) {
    val ui by rvm.ui.collectAsState()
    // matches are long: two at a time by default (ones read with older rules only when ticked)
    var picked by remember(ui.hits) { mutableStateOf(ui.hits.filter { it.url !in readUrls && it.url !in stale }.take(2).map { it.url }.toSet()) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Match videos for ${deck.title}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = { rvm.clearSearch() }) { Icon(Icons.Default.Close, "Close") }
            }
            if (ui.searching) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                ReplayNote("Searching YouTube…")
            }
            ui.searchError?.let { ReplayNote(it, error = true) }
            ui.hits.forEach { h ->
                val done = h.url in readUrls
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = !done) {
                    picked = if (h.url in picked) picked - h.url else picked + h.url
                }) {
                    Checkbox(checked = h.url in picked, onCheckedChange = null, enabled = !done)
                    Column(Modifier.weight(1f)) {
                        Text(h.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        ReplayNote(listOfNotNull(h.uploader.ifBlank { null }, h.date.ifBlank { null },
                            ComboViewModel.clock(h.seconds.toDouble()), if (done) "already read" else if (h.url in stale) "read with older rules: tick to read again" else null).joinToString(" · "))
                    }
                }
            }
            if (ui.hits.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Button(onClick = { rvm.read(deck, vm.db, ui.hits.filter { it.url in picked }) }, enabled = picked.isNotEmpty()) {
                    Text("Read ${picked.size} match${if (picked.size == 1) "" else "es"}")
                }
                ReplayNote("A 20-minute match costs roughly what 300 images do. Each video is deleted after reading.")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MatchCard(vm: AppViewModel, rvm: ReplayViewModel, deckId: String, m: MatchReplay, openCards: (List<Long>, Int) -> Unit) {
    val uri = LocalUriHandler.current
    var open by rememberSaveable("match" + m.url) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val result = when {
                        m.wins > m.losses -> "Won ${m.wins}–${m.losses}"
                        m.wins < m.losses -> "Lost ${m.wins}–${m.losses}"
                        m.games.any { it.won != null } -> "${m.wins}–${m.losses}"
                        else -> "Result not shown"
                    }
                    Text("$result vs ${m.opponent}", fontWeight = FontWeight.SemiBold)
                    ReplayNote(listOf(m.uploader, m.date, m.format).filter { it.isNotBlank() }.joinToString(" · "))
                }
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            Text(m.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { if (m.url.startsWith("http")) runCatching { uri.openUri(m.url) } })
            if (m.lessons.isNotEmpty()) {
                ReplayLabel("Takeaways")
                m.lessons.forEach { ReplayNote("• $it") }
            }
            if (open) {
                m.games.forEach { g -> GameSection(vm, g, openCards) }
                if (m.timeline.isNotEmpty()) {
                    ReplayLabel("Cards shown, in order (free reading: no sides or turns)")
                    m.timeline.forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CardThumbnail(vm, e.code, width = 28) { openCards(m.timeline.map { it.code }, m.timeline.indexOf(e)) }
                            Text(e.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { runCatching { uri.openUri(m.url + (if ('?' in m.url) "&" else "?") + "t=${e.t.toInt()}s") } }) {
                                Text(ComboViewModel.clock(e.t))
                            }
                        }
                    }
                }
                if (m.sideChanges.isNotEmpty()) {
                    ReplayLabel("Side deck changes")
                    m.sideChanges.forEach { ReplayNote("• $it") }
                }
                TextButton(onClick = { rvm.forget(deckId, m.url) }) { Text("Remove this match") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameSection(vm: AppViewModel, g: GameRecord, openCards: (List<Long>, Int) -> Unit) {
    val head = buildString {
        append("Game ${g.number}")
        when (g.weFirst) { true -> append(" · went first"); false -> append(" · went second"); null -> {} }
        when (g.won) { true -> append(" · won"); false -> append(" · lost"); null -> {} }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(head, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (g.how.isNotBlank()) ReplayNote(g.how)
        g.turns.forEach { t -> TurnRow(vm, t, openCards) }
        if (g.turningPoints.isNotEmpty()) {
            ReplayLabel("Turning points")
            g.turningPoints.forEach { ReplayNote("• $it") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TurnRow(vm: AppViewModel, t: TurnRecord, openCards: (List<Long>, Int) -> Unit) {
    Surface(
        color = if (t.ours) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Turn ${t.turn} · ${if (t.ours) "you" else "opponent"}: ${t.summary}", style = MaterialTheme.typography.bodyMedium)
            if (t.endBoard.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    t.endBoard.forEachIndexed { i, c -> CardThumbnail(vm, c, width = 32) { openCards(t.endBoard, i) } }
                }
            }
            t.interrupts.forEach { i ->
                ReplayNote("${if (i.ours) "You" else "They"}: ${i.name}" + if (i.on.isNotBlank()) " on ${i.on}" else "")
            }
        }
    }
}

/** What happened to the last job that never finished (Android closed the app, or it crashed). */
@Composable
fun InterruptionNote() {
    var note by remember { mutableStateOf(com.ygosandbox.app.work.BackgroundWork.interruption()) }
    val text = note ?: return
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("The last run stopped", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = { com.ygosandbox.app.work.BackgroundWork.dismissInterruption(); note = null }) { Text("OK") }
        }
    }
}

@Composable
private fun ReplayLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun ReplayNote(text: String, error: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}
