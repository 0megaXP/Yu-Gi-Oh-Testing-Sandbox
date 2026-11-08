@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ygosandbox.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.ygosandbox.app.Screen
import com.ygosandbox.app.UiState
import com.ygosandbox.app.analysis.AnalysisReport
import com.ygosandbox.app.analysis.CardScore
import com.ygosandbox.app.analysis.DeckAnalyzer
import com.ygosandbox.app.analysis.RoleGuesser
import com.ygosandbox.app.analysis.Turn
import com.ygosandbox.app.analysis.TurnStats
import com.ygosandbox.app.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "TEST" label; tap or long-press it for an explanation. */
@Composable
fun TestBadge() {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text("Test feature: scores are estimates from simulated opening hands and card roles. Results and rules may change.")
            }
        },
        state = state,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.tertiary,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.clickable { scope.launch { state.show() } },
        ) {
            Text(
                "TEST",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

// ---------------- Deck picker ----------------

@Composable
fun AnalysisScreen(ui: UiState, vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        Header("Analysis", onBack = { vm.back() }) { TestBadge(); Spacer(Modifier.width(12.dp)) }
        Text(
            "Pick one of your decks. The app draws ${DeckAnalyzer.HANDS} opening hands going first and going second, " +
                "and rates each card by how much it improves the hands it's in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        if (ui.myDecks.isEmpty()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No decks yet. Create or import one in My decks first.")
                Button(onClick = { vm.open(Screen.MyDecks) }) { Text("Open My decks") }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(ui.myDecks, key = { it.id }) { d ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().clickable { vm.open(Screen.AnalysisDeck(d.id)) },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(d.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${d.format.label} · Main ${d.main.size} · Extra ${d.extra.size} · Side ${d.side.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Report ----------------

@Composable
fun AnalysisDeckScreen(ui: UiState, vm: AppViewModel, id: String) {
    val deck = ui.myDecks.firstOrNull { it.id == id }
    if (deck == null) {
        Column { Header("Analysis", onBack = { vm.back() }) }
        return
    }
    var turn by rememberSaveable { mutableStateOf(Turn.FIRST) }
    var editing by remember { mutableStateOf<CardScore?>(null) }
    var pager by remember { mutableStateOf<Int?>(null) }

    val report by produceState<AnalysisReport?>(null, deck.main, deck.extra, deck.roles, ui.dbRevision) {
        value = withContext(Dispatchers.Default) { DeckAnalyzer.analyze(deck, vm.db) }
    }
    val sorted = remember(report, turn) {
        report?.cards.orEmpty().sortedWith(compareByDescending<CardScore> { it.score.getValue(turn) }.thenBy { it.card?.name ?: "" })
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header(deck.name, onBack = { vm.back() }) { TestBadge(); Spacer(Modifier.width(12.dp)) }
            val r = report
            if (r == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                item(key = "top") {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Turn.entries.forEach { t ->
                                FilterChip(selected = turn == t, onClick = { turn = t }, label = { Text("${t.label} (${t.handSize})") })
                            }
                        }
                        if (r.mainCount < 40) Text(
                            "The Main Deck has ${r.mainCount} cards. Results are more meaningful with a full deck of 40+.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (r.hands > 0) StatsCard(r.stats.getValue(turn), turn)
                        Text(
                            "Card scores: 50 is neutral, higher means hands with the card are better than hands without it. " +
                                "Tap a card to set its roles; the guesses come from its text and are often wrong.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(sorted, key = { it.cardId }) { c ->
                    ScoreRow(c, turn, onRoles = { editing = c }, onOpen = {
                        pager = sorted.indexOf(c).coerceAtLeast(0)
                    })
                }
                if (r.extraDeck.isNotEmpty()) item(key = "extra") {
                    Text(
                        "Extra Deck (${r.extraDeck.size} different cards) isn't drawn, so it isn't scored yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        pager?.let { start ->
            CardPager(sorted.map { PagerItem(it.cardId, it.card?.name ?: "Unknown card", it.card) }, start, deck.format, onClose = { pager = null })
        }
    }

    editing?.let { c ->
        RoleDialog(
            c,
            onDismiss = { editing = null },
            onSave = { roles -> editing = null; vm.setRoles(deck.id, c.cardId, roles) },
            onReset = { editing = null; vm.setRoles(deck.id, c.cardId, null) },
        )
    }
}

@Composable
private fun StatsCard(s: TurnStats, turn: Turn) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Opening hands · ${turn.label.lowercase()}", fontWeight = FontWeight.SemiBold)
            StatLine("Can start a combo", s.playable, "a starter, or 2 extenders")
            StatLine("Strong start", s.strong, "2 starters, or starter + extender")
            StatLine("Has interaction", s.interaction, "hand trap or interruption")
            if (turn == Turn.SECOND) StatLine("Has a board breaker", s.breaker, null)
            StatLine("Bricked", s.bricked, "2 or more bricks", bad = true)
            StatLine("Average hand quality", s.quality, null)
        }
    }
}

@Composable
private fun StatLine(label: String, value: Float, hint: String?, bad: Boolean = false) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(pct(value), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) },
            color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        )
        if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ScoreRow(c: CardScore, turn: Turn, onRoles: () -> Unit, onOpen: () -> Unit) {
    val score = c.score.getValue(turn)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onRoles).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = c.card?.imageUrl(small = true) ?: "https://images.ygoprodeck.com/images/cards_small/${c.cardId}.jpg",
            contentDescription = "Show card",
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(40.dp).height(58.dp).clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onOpen),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${c.card?.name ?: "Unknown card"} ×${c.copies}",
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                (if (c.roles.isEmpty()) "No role" else c.roles.joinToString(", ") { it.label }) +
                    (if (c.rolesByHand) " (set by you)" else " (guessed)"),
                style = MaterialTheme.typography.labelSmall,
                color = if (c.rolesByHand) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (c.reasons.isNotEmpty()) Text(
                c.reasons.joinToString(" · ") { it.replace(' ', ' ') },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(44.dp)) {
            Text(
                "$score",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = when {
                    score >= 60 -> MaterialTheme.colorScheme.primary
                    score <= 40 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            LinearProgressIndicator(
                progress = { score / 100f },
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }
    }
}

@Composable
private fun RoleDialog(c: CardScore, onDismiss: () -> Unit, onSave: (Set<Role>) -> Unit, onReset: () -> Unit) {
    val chosen = remember { mutableStateListOf(*c.roles.toTypedArray()) }
    val guessed = remember(c) { c.card?.let(RoleGuesser::guess).orEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(c.card?.name ?: "Card roles") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "What does this card do in this deck? Pick any that apply.",
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Role.entries.forEach { r ->
                        FilterChip(
                            selected = r in chosen,
                            onClick = { if (r in chosen) chosen.remove(r) else chosen.add(r) },
                            label = { Text(r.label) },
                        )
                    }
                }
                Role.entries.forEach { r ->
                    Text("${r.label}: ${r.short}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "Automatic guess: " + (if (guessed.isEmpty()) "no role" else guessed.joinToString(", ") { it.label }),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(chosen.toSet()) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (c.rolesByHand) TextButton(onClick = onReset) { Text("Use guess") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
