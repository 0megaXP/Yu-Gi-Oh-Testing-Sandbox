@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ygosandbox.app.ui

import androidx.compose.foundation.clickable
import com.ygosandbox.app.Screen
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ygosandbox.app.AppViewModel
import com.ygosandbox.app.UiState
import com.ygosandbox.app.history.HistoryGame
import com.ygosandbox.app.history.HistoryMatch
import com.ygosandbox.app.history.MatchHistory
import com.ygosandbox.app.platform.AppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WIN = Color(0xFF2E7D32)
private val LOSS = Color(0xFFC62828)
private val DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm")

private fun record(w: Int, l: Int): String {
    val pct = if (w + l == 0) "" else " (${(100.0 * w / (w + l)).toInt()}%)"
    return "$w–$l$pct"
}

private fun deckLabel(name: String) = name.ifBlank { "Unknown deck" }

/**
 * Match history: every YGO Omega replay in the replay folder, grouped into Best of 3 matches,
 * with your record overall, per deck and against each deck.
 */
@Composable
fun MatchHistoryScreen(ui: UiState, vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    val platform = remember { AppPlatform.current }
    // the same folder as the replay analysis in Settings and the Replays tab
    val prefs = remember { platform.preferences("replays") }
    var folder by remember { mutableStateOf(prefs.getString("omega_folder", null) ?: platform.omegaReplays?.path ?: "") }
    var note by remember { mutableStateOf<String?>(null) }
    var looking by remember { mutableStateOf(false) }
    val state by MatchHistory.state.collectAsState()
    // archetype tags picked for each side; none picked = any archetype
    var myDecks by remember { mutableStateOf(emptySet<String>()) }
    var theirDecks by remember { mutableStateOf(emptySet<String>()) }
    val open = remember { mutableStateListOf<Long>() }

    fun refresh() = readFolder(scope, folder, vm, onLooking = { looking = it }, onNote = { note = it })

    // read the folder once per app start (new replays since last time); Refresh reads it again
    LaunchedEffect(folder, ui.dbStatus) {
        if (folder.isNotBlank() && !MatchHistory.readThisSession && vm.db.cards.isNotEmpty()) {
            MatchHistory.readThisSession = true
            refresh()
        }
    }

    val all = remember(state.revision) { MatchHistory.matches() }
    val matches = remember(all, myDecks, theirDecks) {
        all.filter { (myDecks.isEmpty() || it.myDeck in myDecks) && (theirDecks.isEmpty() || it.theirDeck in theirDecks) }
    }

    Column(Modifier.fillMaxSize()) {
        Header("Match history", onBack = { vm.back() }) {
            IconButton(enabled = folder.isNotBlank() && !state.running && !looking, onClick = { refresh() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Read the replay folder again")
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Every YGO Omega replay in your replay folder, grouped into Best of 3 matches: games between " +
                            "the same two players saved one after another.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (folder.isBlank()) {
                        Text("Choose YGO Omega's replay folder to start." +
                            if (platform.canTypeFolder) " On Windows it is in %USERPROFILE%\\AppData\\LocalLow\\Duelists Unite\\YGO Omega." else "",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val pick = {
                            scope.launch {
                                platform.pickFolder("YGO Omega replay folder", folder.ifBlank { null })?.let {
                                    folder = it
                                    prefs.edit().putString("omega_folder", it).apply()
                                    MatchHistory.readThisSession = true
                                    readFolder(scope, it, vm, onLooking = { b -> looking = b }, onNote = { n -> note = n })
                                }
                            }
                            Unit
                        }
                        if (folder.isBlank()) Button(onClick = pick) { Text("Choose folder") }
                        else OutlinedButton(enabled = !state.running && !looking, onClick = pick) { Text("Change folder") }
                    }
                    if (state.running || looking) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (looking) "Looking through the folder…" else state.progress, style = MaterialTheme.typography.bodySmall)
                    }
                    (note ?: state.note)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (all.isNotEmpty()) {
                item {
                    ArchetypeTags("Your deck", all.map { it.myDeck }, myDecks) { a ->
                        myDecks = if (a == null) emptySet() else if (a in myDecks) myDecks - a else myDecks + a
                    }
                }
                item {
                    ArchetypeTags("Against", all.map { it.theirDeck }, theirDecks) { a ->
                        theirDecks = if (a == null) emptySet() else if (a in theirDecks) theirDecks - a else theirDecks + a
                    }
                }
                item { Summary(matches) }
            }
            if (matches.isEmpty() && all.isNotEmpty()) item { Text("No matches with those archetypes.") }
            items(matches, key = { it.games.first().id }) { m ->
                val key = m.start
                MatchCard(vm, m, expanded = key in open) { if (key in open) open.remove(key) else open.add(key) }
            }
        }
    }
}

private fun readFolder(scope: CoroutineScope, folder: String, vm: AppViewModel, onLooking: (Boolean) -> Unit, onNote: (String?) -> Unit) {
    if (folder.isBlank()) return
    scope.launch {
        onLooking(true)
        onNote(null)
        val known = MatchHistory.known()
        val files = runCatching { AppPlatform.current.readFolder(folder, ".bytes") { _, _ -> true } }
        onLooking(false)
        files.onSuccess { fs ->
            val fresh = fs.count { (_, b) -> com.ygosandbox.app.combos.OmegaImport.id(b) !in known }
            if (fresh == 0) onNote(if (fs.isEmpty()) "No YGO Omega replays in that folder." else "No new games.")
            // all the files: games read before file names were kept get theirs (to watch them)
            if (fs.isNotEmpty()) MatchHistory.refresh(fs, vm.db)
        }.onFailure { e -> onNote("Couldn't read the folder: ${e.message ?: e.javaClass.simpleName}") }
    }
}

/**
 * Archetype tags from the history, most played first: tap tags to keep only those matches
 * (several may be on), "Any" clears them. Long lists show the first ones until expanded.
 */
@Composable
private fun ArchetypeTags(label: String, archetypes: List<String>, selected: Set<String>, onTag: (String?) -> Unit) {
    var more by remember { mutableStateOf(false) }
    val options = remember(archetypes) { archetypes.groupingBy { it }.eachCount().entries.sortedByDescending { it.value } }
    val shown = if (more) options else options.take(TAGS_SHOWN).let { top ->
        // a picked tag stays visible even when folded away
        top + options.drop(TAGS_SHOWN).filter { it.key in selected }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = selected.isEmpty(), onClick = { onTag(null) }, label = { Text("Any") })
            shown.forEach { (a, n) ->
                FilterChip(selected = a in selected, onClick = { onTag(a) }, label = { Text("${deckLabel(a)} · $n") })
            }
            if (options.size > TAGS_SHOWN) TextButton(onClick = { more = !more }) {
                Text(if (more) "Fewer" else "+${options.size - TAGS_SHOWN} more")
            }
        }
    }
}

private const val TAGS_SHOWN = 10

@Composable
private fun Summary(matches: List<HistoryMatch>) {
    val games = matches.flatMap { it.games }
    fun gw(l: List<HistoryGame>) = record(l.count { it.won == true }, l.count { it.won == false })
    val opponents = remember(matches) {
        matches.groupBy { it.theirDeck }.map { (d, ms) -> Triple(d, ms.count { it.won == true }, ms.count { it.won == false }) }
            .sortedByDescending { it.second + it.third }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Record", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Matches ${record(matches.count { it.won == true }, matches.count { it.won == false })} · Games ${gw(games)}")
            Text(
                "Games going first ${gw(games.filter { it.wentFirst })} · going second ${gw(games.filter { !it.wentFirst })}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (opponents.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text("Against", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                opponents.take(8).forEach { (d, w, l) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(deckLabel(d), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(record(w, l), style = MaterialTheme.typography.bodySmall,
                            color = if (w > l) WIN else if (l > w) LOSS else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (opponents.size > 8) Text("and ${opponents.size - 8} more decks", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MatchCard(vm: AppViewModel, m: HistoryMatch, expanded: Boolean, onToggle: () -> Unit) {
    val (label, color) = when (m.won) {
        true -> "Won" to WIN
        false -> "Lost" to LOSS
        null -> (if (m.games.any { it.won == null }) "Unfinished" else "Draw") to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val date = remember(m.start) {
        if (m.start <= 0) "" else Instant.ofEpochMilli(m.start).atZone(ZoneId.systemDefault()).format(DATE)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = color, shape = RoundedCornerShape(8.dp)) {
                    Text("$label ${m.wins}–${m.losses}", color = Color.White, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                Text(if (m.games.size == 1) "1 game" else "${m.games.size} games", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${deckLabel(m.myDeck)} vs ${deckLabel(m.theirDeck)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                m.games.forEachIndexed { i, g ->
                    val c = when (g.won) { true -> WIN; false -> LOSS; null -> MaterialTheme.colorScheme.onSurfaceVariant }
                    Text(
                        "G${i + 1} ${when (g.won) { true -> "W"; false -> "L"; null -> "–" }} · ${if (g.wentFirst) "1st" else "2nd"}",
                        color = c, style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (expanded) m.games.forEachIndexed { i, g -> GameDetail(vm, i + 1, g) }
        }
    }
}

@Composable
private fun GameDetail(vm: AppViewModel, number: Int, g: HistoryGame) {
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val result = when (g.won) { true -> "Won"; false -> "Lost"; null -> "No result" }
        Text(
            "Game $number · ${if (g.wentFirst) "went first" else "went second"} · $result · ${g.how} · ${g.turns} turns",
            style = MaterialTheme.typography.labelLarge,
        )
        if (g.myDeck.isNotBlank() || g.theirDeck.isNotBlank()) Text(
            "${deckLabel(g.myDeck)} vs ${deckLabel(g.theirDeck)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (g.myHand.isNotEmpty()) {
            Text("Your opening hand", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                g.myHand.forEach { CardThumbnail(vm, it, width = 40) }
            }
        }
        OutlinedButton(onClick = { vm.open(Screen.ReplayViewer(g.id)) }) { Text("Watch replay") }
        val theirs = g.theirCards.mapNotNull { vm.db.find(it)?.name }.distinct()
        if (theirs.isNotEmpty()) Text(
            "The opponent played: " + theirs.take(12).joinToString(", ") + if (theirs.size > 12) "…" else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
