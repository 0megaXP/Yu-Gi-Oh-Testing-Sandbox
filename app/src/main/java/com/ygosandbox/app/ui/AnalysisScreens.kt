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
import androidx.compose.material3.OutlinedButton
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
import com.ygosandbox.app.analysis.CardInfo
import com.ygosandbox.app.analysis.Odds
import com.ygosandbox.app.analysis.OddsTable
import com.ygosandbox.app.analysis.RoleGuesser
import com.ygosandbox.app.model.Opt
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
                Text("Test feature: roles and once-per-turn are guessed from card text and may be wrong. The odds are exact for the roles shown.")
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
            "Pick one of your decks to see each card's role and once-per-turn rule, and the exact odds of " +
                "opening playable cards and hand traps.",
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

private enum class HandSize(val label: String, val cards: Int) { FIRST("Going first", 5), SECOND("Going second", 6) }

@Composable
fun AnalysisDeckScreen(ui: UiState, vm: AppViewModel, id: String) {
    val deck = ui.myDecks.firstOrNull { it.id == id }
    if (deck == null) {
        Column { Header("Analysis", onBack = { vm.back() }) }
        return
    }
    var hand by rememberSaveable { mutableStateOf(HandSize.FIRST) }
    val playableRoles = remember { mutableStateListOf(Role.STARTER, Role.SEARCHER) }
    var editing by remember { mutableStateOf<CardInfo?>(null) }
    var pager by remember { mutableStateOf<Int?>(null) }

    val cards = remember(deck.main, deck.roles, ui.dbRevision) { Odds.cards(deck, vm.db) }
    val playable = remember(cards, playableRoles.toList(), hand) {
        Odds.table(cards, playableRoles.toSet(), deck.main.size, hand.cards)
    }
    val handTraps = remember(cards, hand) { Odds.table(cards, setOf(Role.HAND_TRAP), deck.main.size, hand.cards) }
    // Cards listed by their first role, in role order; cards without a role last.
    val ordered = remember(cards) {
        cards.sortedWith(
            compareBy<CardInfo> { c -> c.roles.minOfOrNull { it.ordinal } ?: Role.entries.size }
                .thenBy { it.card?.name ?: "" },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header(deck.name, onBack = { vm.back() }) { TestBadge(); Spacer(Modifier.width(12.dp)) }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                item(key = "top") {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HandSize.entries.forEach { h ->
                                FilterChip(selected = hand == h, onClick = { hand = h }, label = { Text("${h.label} (${h.cards})") })
                            }
                        }
                        if (deck.main.size < 40) Text(
                            "The Main Deck has ${deck.main.size} cards; a legal deck needs 40.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )

                        OutlinedButton(onClick = { vm.open(Screen.SidePlan(deck.id)) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Side plan against another deck")
                        }

                        SectionTitle("Playable cards")
                        Text("Count as playable:", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(Role.STARTER, Role.SEARCHER, Role.EXTENDER).forEach { r ->
                                FilterChip(
                                    selected = r in playableRoles,
                                    onClick = { if (r in playableRoles) playableRoles.remove(r) else playableRoles.add(r) },
                                    label = { Text(r.label) },
                                )
                            }
                        }
                        OddsCard(playable, "playable", hand.cards)

                        SectionTitle("Hand traps")
                        OddsCard(handTraps, "hand trap", hand.cards)

                        Text(
                            "Exact odds for a ${hand.cards}-card hand from ${deck.main.size} cards. " +
                                "\"Hard OPT once\" counts 2+ copies of the same hard once-per-turn card as one, since only one can be used.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SectionTitle("Cards")
                        Text(
                            "Roles are guessed from the card text; tap a card to change them. Once per turn is read from the text.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(ordered, key = { it.cardId }) { c ->
                    InfoRow(c, onRoles = { editing = c }, onOpen = { pager = ordered.indexOf(c).coerceAtLeast(0) })
                }
            }
        }
        pager?.let { start ->
            CardPager(ordered.map { PagerItem(it.cardId, it.card?.name ?: "Unknown card", it.card) }, start, deck.format, onClose = { pager = null })
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
private fun OddsCard(t: OddsTable, what: String, handSize: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${t.cardsInDeck} $what card${if (t.cardsInDeck == 1) "" else "s"} in the deck",
                fontWeight = FontWeight.SemiBold,
            )
            Row {
                Text("In hand", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text("Exactly", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
                Text("At least", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
                Text("Hard OPT once", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(80.dp))
            }
            t.rows.filter { it.k <= handSize }.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${r.k}", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Text(oddsPct(r.exactly), modifier = Modifier.width(64.dp))
                    Text(oddsPct(r.atLeast), modifier = Modifier.width(64.dp), fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                    Text(oddsPct(r.atLeastUsable), modifier = Modifier.width(80.dp))
                }
                LinearProgressIndicator(
                    progress = { r.atLeast.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                )
            }
            val none = 1.0 - (t.rows.firstOrNull()?.atLeast ?: 0.0)
            Text(
                "No $what card: ${oddsPct(none)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 0.4172 → "41.7%", tiny values → "<0.1%". */
private fun oddsPct(p: Double): String = when {
    p <= 0.0 -> "0%"
    p < 0.001 -> "<0.1%"
    else -> String.format(java.util.Locale.US, "%.1f%%", p * 100)
}

@Composable
private fun InfoRow(c: CardInfo, onRoles: () -> Unit, onOpen: () -> Unit) {
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
            Text(
                c.opt.label,
                style = MaterialTheme.typography.labelSmall,
                color = when (c.opt) {
                    Opt.HARD -> MaterialTheme.colorScheme.tertiary
                    Opt.SOFT -> MaterialTheme.colorScheme.secondary
                    Opt.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun RoleDialog(c: CardInfo, onDismiss: () -> Unit, onSave: (Set<Role>) -> Unit, onReset: () -> Unit) {
    val chosen = remember { mutableStateListOf(*c.roles.toTypedArray()) }
    val guessed = remember(c) { c.card?.let(RoleGuesser::guess).orEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(c.card?.name ?: "Card roles") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What does this card do in this deck? Pick any that apply.", style = MaterialTheme.typography.bodySmall)
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
                    "Automatic guess: " + (if (guessed.isEmpty()) "no role" else guessed.joinToString(", ") { it.label }) +
                        " · ${c.opt.label}",
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
