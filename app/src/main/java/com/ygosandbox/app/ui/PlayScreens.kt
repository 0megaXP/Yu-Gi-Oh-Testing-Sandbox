@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.ygoengine.AnalysisResult
import com.ygoengine.EngineCard
import com.ygoengine.Line
import com.ygoengine.Progress
import com.ygosandbox.app.AppViewModel
import com.ygosandbox.app.UiState
import com.ygosandbox.app.model.GameFormat
import com.ygosandbox.app.play.DeckChoice
import com.ygosandbox.app.play.Depth
import com.ygosandbox.app.play.HAND_SIZE

// ---------------- Shared pieces ----------------

private fun imageOf(vm: AppViewModel, code: Long): String =
    vm.db.find(code)?.imageUrl(small = true) ?: "https://images.ygoprodeck.com/images/cards_small/$code.jpg"

/** Small card picture; [onClick] opens it full size. */
@Composable
fun CardThumbnail(vm: AppViewModel, code: Long, width: Int = 44, onClick: (() -> Unit)? = null) {
    AsyncImage(
        model = imageOf(vm, code),
        contentDescription = vm.db.find(code)?.name,
        contentScale = ContentScale.Crop,
        modifier = Modifier.width(width.dp).aspectRatio(0.686f).clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

/** A field that opens a deck list when tapped. */
@Composable
fun DeckField(label: String, choice: DeckChoice?, choices: List<DeckChoice>, onPick: (DeckChoice) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable { open = true },
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(choice?.name ?: "Tap to choose", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (choice != null) Text(
                choice.detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                if (choices.isEmpty()) {
                    Text("No decks yet. Build one in My decks, or run an Archetype search: its tournament lists show up here.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(choices, key = { it.key }) { c ->
                            Column(
                                Modifier.fillMaxWidth().clickable { onPick(c); open = false }.padding(vertical = 8.dp),
                            ) {
                                Text(c.name, fontWeight = FontWeight.Medium)
                                Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}

@Composable
fun EngineProgress(p: Progress, preparing: Boolean, onStop: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val text = when {
                preparing -> "Preparing the engine (first time only)…"
                p.phase == "search" -> "Searching your turn… ${p.done} / ${p.total}"
                p.phase.startsWith("verify") -> "Playing their turn against line ${p.phase.removePrefix("verify ")}…"
                p.phase == "simulate" -> "Simulating their opening turns… ${p.done} / ${p.total}"
                else -> "Starting…"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
                TextButton(onClick = onStop) { Text("Stop") }
            }
            if (p.total > 0) LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun MissingCardsNote(vm: AppViewModel, codes: List<Long>) {
    if (codes.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
        Text(
            "The rules engine can't play these cards yet, so they do nothing in this analysis: " +
                codes.joinToString(", ") { vm.db.find(it)?.name ?: "#$it" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

private fun score(v: Double) = "${(v * 100).toInt()}/100"

// ---------------- Play engine screen ----------------

@Composable
fun PlayScreen(ui: UiState, vm: AppViewModel) {
    val play = ui.play
    val setup = play.setup
    val choices = remember(ui.myDecks, ui.decks) { vm.playDeckChoices() }
    val mine = choices.firstOrNull { it.key == setup.myDeck }
    val theirs = choices.firstOrNull { it.key == setup.opponentDeck }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = my hand, false = theirs
    var pager by remember { mutableStateOf<Pair<List<Long>, Int>?>(null) }
    val running = play.progress != null

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header("Play engine", onBack = { vm.back() }) { TestBadge(); Spacer(Modifier.width(12.dp)) }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "intro") {
                    Text(
                        "Plays your hand with the real card rules (EDOPro scripts), finds your best lines and end boards, " +
                            "then plays the opponent's next turn against each board.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item(key = "decks") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DeckField("Your deck", mine, choices) { c -> vm.updatePlaySetup { it.copy(myDeck = c.key) } }
                        DeckField("Opponent's deck", theirs, choices) { c -> vm.updatePlaySetup { it.copy(opponentDeck = c.key) } }
                    }
                }
                item(key = "options") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(setup.goingFirst, { vm.updatePlaySetup { it.copy(goingFirst = true) } }, { Text("Going first") })
                            FilterChip(!setup.goingFirst, { vm.updatePlaySetup { it.copy(goingFirst = false) } }, { Text("Going second") })
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!setup.real, { vm.updatePlaySetup { it.copy(real = false) } }, { Text("Practice") })
                            FilterChip(setup.real, { vm.updatePlaySetup { it.copy(real = true) } }, { Text("Real play") })
                        }
                        Text(
                            if (setup.real) "Real play: the engine knows only your hand and samples theirs from their list."
                            else "Practice: the engine sees both hands. Leave theirs empty for a different random hand in every sample.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Depth.entries.forEach { d ->
                                FilterChip(setup.depth == d, { vm.updatePlaySetup { it.copy(depth = d) } }, { Text("${d.label} · ${d.hint}") })
                            }
                        }
                    }
                }
                item(key = "myhand") {
                    HandPanel(
                        vm, "Your hand", setup.myHand, enabled = mine != null,
                        note = if (!setup.goingFirst) "Going second you draw a 6th card at the start of your turn." else null,
                        onPick = { picking = true }, onRandom = { vm.dealHand(true) },
                        onRemove = { i -> vm.updatePlaySetup { it.copy(myHand = it.myHand.filterIndexed { j, _ -> j != i }) } },
                    )
                }
                if (!setup.real) item(key = "opphand") {
                    HandPanel(
                        vm, "Opponent's hand (optional)", setup.opponentHand, enabled = theirs != null,
                        note = if (setup.opponentHand.isEmpty()) "Empty: a random hand from their list in every sample." else null,
                        onPick = { picking = false }, onRandom = { vm.dealHand(false) },
                        onRemove = { i -> vm.updatePlaySetup { it.copy(opponentHand = it.opponentHand.filterIndexed { j, _ -> j != i }) } },
                    )
                }
                item(key = "run") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        play.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        val p = play.progress
                        if (p != null) EngineProgress(p, play.preparing, onStop = { vm.stopPlay() })
                        else Button(onClick = { vm.runPlay() }, modifier = Modifier.fillMaxWidth()) { Text("Find best plays") }
                    }
                }
                val result = play.result
                if (result != null && !running) {
                    resultItems(this, vm, result, play.resultSetup?.goingFirst ?: true) { codes, i -> pager = codes to i }
                }
            }
        }
        pager?.let { (codes, start) ->
            CardPager(
                codes.map { PagerItem(it, vm.db.find(it)?.name ?: "Unknown card", vm.db.find(it)) },
                start, GameFormat.TCG, onClose = { pager = null },
            )
        }
    }

    picking?.let { forMe ->
        val deck = if (forMe) mine else theirs
        if (deck != null) {
            HandPickerDialog(
                vm, deck, if (forMe) setup.myHand else setup.opponentHand,
                onDone = { hand ->
                    vm.updatePlaySetup { if (forMe) it.copy(myHand = hand) else it.copy(opponentHand = hand) }
                    picking = null
                },
                onDismiss = { picking = null },
            )
        } else {
            picking = null
        }
    }
}

@Composable
private fun HandPanel(
    vm: AppViewModel,
    title: String,
    hand: List<Long>,
    enabled: Boolean,
    note: String?,
    onPick: () -> Unit,
    onRandom: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$title · ${hand.size}/$HAND_SIZE", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onPick, enabled = enabled) { Text("Pick") }
                TextButton(onClick = onRandom, enabled = enabled) { Text("Random") }
            }
            if (hand.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    hand.forEachIndexed { i, code -> CardThumbnail(vm, code, width = 52) { onRemove(i) } }
                }
                Text("Tap a card to remove it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (!enabled) {
                Text("Choose the deck first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Pick up to 5 cards from a deck's Main Deck; copies are limited to what the deck has. */
@Composable
private fun HandPickerDialog(vm: AppViewModel, deck: DeckChoice, current: List<Long>, onDone: (List<Long>) -> Unit, onDismiss: () -> Unit) {
    val chosen = remember { mutableStateListOf(*current.toTypedArray()) }
    val distinct = remember(deck) {
        deck.main.groupingBy { it }.eachCount().entries
            .sortedBy { vm.db.find(it.key)?.name ?: "" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hand · ${chosen.size}/$HAND_SIZE") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (chosen.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    chosen.forEachIndexed { i, code -> CardThumbnail(vm, code, width = 40) { chosen.removeAt(i) } }
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(distinct, key = { it.key }) { (code, copies) ->
                        val left = copies - chosen.count { it == code }
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = left > 0 && chosen.size < HAND_SIZE) { chosen.add(code) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CardThumbnail(vm, code, width = 34)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                vm.db.find(code)?.name ?: "#$code",
                                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = if (left > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text("×$left", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(chosen.toList()) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------- Results ----------------

private fun resultItems(
    scope: androidx.compose.foundation.lazy.LazyListScope,
    vm: AppViewModel,
    r: AnalysisResult,
    goingFirst: Boolean,
    onOpen: (List<Long>, Int) -> Unit,
) = with(scope) {
    item(key = "summary") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("Results")
            MissingCardsNote(vm, r.missingCards)
            if (r.cancelled) Text(
                "Stopped early: these are the best lines found so far.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                "Searched ${r.iterations} positions in ${r.seconds.toInt()} s · start of game: ${score(r.currentScore)} for you",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Their deck relies on: ${r.opponentProfile.summary}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (r.lines.isEmpty()) Text("No line found. Try a deeper search.")
        }
    }
    itemsIndexed(r.lines, key = { i, _ -> "line$i" }) { i, line ->
        LineCard(vm, i + 1, line, goingFirst, onOpen)
    }
}

@Composable
private fun LineCard(vm: AppViewModel, rank: Int, line: Line, goingFirst: Boolean, onOpen: (List<Long>, Int) -> Unit) {
    var showSteps by rememberSaveable(line.firstMove) { mutableStateOf(rank == 1) }
    var showReply by rememberSaveable(line.firstMove + "r") { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#$rank", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(line.firstMove, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Score ${score(line.combined)} · end of your turn ${score(line.ownTurnScore)}" +
                            (line.afterOpponent?.let { " · after their best turn ${score(it)}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (line.endBoard.isNotEmpty()) {
                Text("End board", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                BoardRow(vm, line.endBoard, onOpen)
            }
            if (line.interrupts.isNotEmpty()) {
                Text("Can stop on their turn", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                line.interrupts.forEach { c ->
                    Text(
                        "• ${c.name} (${c.where}): ${c.note}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Toggle(if (showSteps) "Hide steps" else "Steps (${line.moves.size})", showSteps) { showSteps = !showSteps }
            if (showSteps) Steps(line.moves, otherPrefix = "Opp: ")

            if (line.opponentReply.isNotEmpty()) {
                Toggle(if (showReply) "Hide their reply" else "Their strongest reply", showReply) { showReply = !showReply }
                if (showReply) {
                    Steps(line.opponentReply, otherPrefix = "You: ", mineIsOther = true)
                    if (line.boardAfter.isNotEmpty()) {
                        Text("Your cards after their turn", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        BoardRow(vm, line.boardAfter, onOpen)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardRow(vm: AppViewModel, cards: List<EngineCard>, onOpen: (List<Long>, Int) -> Unit) {
    val codes = cards.map { it.code }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(cards) { i, c ->
            Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CardThumbnail(vm, c.code, width = 56) { onOpen(codes, i) }
                Text(
                    listOf(c.where.replace("Monster Zone", "Monster").replace("S/T Zone", "S/T"), c.note)
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Toggle(text: String, open: Boolean, onClick: () -> Unit) {
    Row(Modifier.clickable(onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.primary)
        Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

/** Numbered steps. Lines starting with [otherPrefix] belong to the other player and are dimmed. */
@Composable
private fun Steps(steps: List<String>, otherPrefix: String, mineIsOther: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEachIndexed { i, s ->
            val other = s.startsWith(otherPrefix)
            val text = s.removePrefix(otherPrefix).replace("  →  ", " → ")
            val who = when {
                other && mineIsOther -> "You: "
                other -> "Opp: "
                mineIsOther -> "Opp: "
                else -> ""
            }
            Row {
                Text("${i + 1}.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(26.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    who + text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (other != mineIsOther) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// ---------------- Side plan ----------------

@Composable
fun SidePlanScreen(ui: UiState, vm: AppViewModel, deckId: String) {
    val deck = ui.myDecks.firstOrNull { it.id == deckId }
    if (deck == null) {
        Column { Header("Side plan", onBack = { vm.back() }) }
        return
    }
    val play = ui.play
    val choices = remember(ui.myDecks, ui.decks) { vm.playDeckChoices().filter { it.key != "my:$deckId" } }
    val opponent = choices.firstOrNull { it.key == play.sideOpponent && play.sideDeck == deckId }
    var pager by remember { mutableStateOf<Pair<List<Long>, Int>?>(null) }
    val result = play.sideResult?.takeIf { play.sideDeck == deckId && play.sideProgress == null }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header("Side plan: ${deck.name}", onBack = { vm.back() }) { TestBadge(); Spacer(Modifier.width(12.dp)) }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "setup") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "What to side in and out against one deck, going first and going second. Cards are valued " +
                                "by what the opponent's deck relies on (read from the card scripts).",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (deck.side.isEmpty()) Text(
                            "This deck has no Side Deck yet: add cards to its Side in My decks.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        )
                        DeckField("Opponent's deck", opponent, choices) { c -> vm.setSideOpponent(deckId, c.key) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!play.sideSimulate, { vm.setSideSimulate(false) }, { Text("Fast") })
                            FilterChip(play.sideSimulate, { vm.setSideSimulate(true) }, { Text("Simulate their boards · ~1 min") })
                        }
                        play.sideError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        val p = play.sideProgress
                        if (p != null) EngineProgress(p, play.preparing, onStop = { vm.stopSidePlan() })
                        else Button(onClick = { vm.runSidePlan(deckId) }, modifier = Modifier.fillMaxWidth(),
                            enabled = deck.side.isNotEmpty()) { Text("Make side plan") }
                    }
                }
                if (result != null) {
                    item(key = "summary") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            MissingCardsNote(vm, result.missingCards)
                            Text(
                                "${result.opponent.name} relies on: ${result.opponent.summary}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (result.boardShape.samples > 0) Text(
                                "Their simulated first-turn boards: %.1f monsters, %.1f face-up and %.1f set Spell/Trap-zone cards."
                                    .format(result.boardShape.monsters, result.boardShape.spellTrapFaceUp, result.boardShape.spellTrapSet),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    listOf("Going first" to result.goingFirst, "Going second" to result.goingSecond).forEach { (title, plan) ->
                        item(key = title) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SectionTitle(title)
                                if (plan.sideIn.isEmpty()) Text("No swap clearly improves the deck.", style = MaterialTheme.typography.bodySmall)
                                val codes = (plan.sideIn + plan.sideOut).map { it.card.code }
                                plan.sideIn.forEachIndexed { i, s -> SideRow(vm, "+", s.card.code, s.card.name, s.count, s.why) { pager = codes to i } }
                                plan.sideOut.forEachIndexed { i, s ->
                                    SideRow(vm, "−", s.card.code, s.card.name, s.count, s.why) { pager = codes to plan.sideIn.size + i }
                                }
                            }
                        }
                    }
                }
            }
        }
        pager?.let { (codes, start) ->
            CardPager(
                codes.map { PagerItem(it, vm.db.find(it)?.name ?: "Unknown card", vm.db.find(it)) },
                start, deck.format, onClose = { pager = null },
            )
        }
    }
}

@Composable
private fun SideRow(vm: AppViewModel, sign: String, code: Long, name: String, count: Int, why: String, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            sign, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = if (sign == "+") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.width(20.dp),
        )
        CardThumbnail(vm, code, width = 36, onClick = onOpen)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("${count}× $name", fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(why, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
