@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ygosandbox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ygoengine.BotLevel
import com.ygoengine.DuelCard
import com.ygoengine.DuelDecision
import com.ygoengine.DuelState
import com.ygoengine.PlayerState
import com.ygoengine.ZoneCard
import com.ygosandbox.app.AppViewModel
import com.ygosandbox.app.UiState
import com.ygosandbox.app.model.GameFormat
import com.ygosandbox.app.play.DuelMode
import com.ygosandbox.app.play.DuelUi
import com.ygosandbox.app.play.FirstPlayer

@Composable
fun DuelScreen(ui: UiState, vm: AppViewModel) {
    val duel = ui.duel
    var pager by remember { mutableStateOf<Pair<List<Long>, Int>?>(null) }
    Box(Modifier.fillMaxSize()) {
        if (!duel.active) {
            DuelSetupScreen(ui, vm)
        } else {
            DuelBoardScreen(duel, vm, onOpen = { codes, i -> pager = codes to i })
        }
        pager?.let { (codes, start) ->
            CardPager(
                codes.map { PagerItem(it, vm.db.find(it)?.name ?: "Unknown card", vm.db.find(it)) },
                start, GameFormat.TCG, onClose = { pager = null },
            )
        }
    }
}

// ---------------- Setup ----------------

@Composable
private fun DuelSetupScreen(ui: UiState, vm: AppViewModel) {
    val setup = ui.duel.setup
    val choices = remember(ui.myDecks, ui.decks) { vm.playDeckChoices() }
    val human = setup.mode == DuelMode.HUMAN_VS_BOT
    Column(Modifier.fillMaxSize()) {
        Header("Duel simulator", onBack = { vm.back() }) { TestBadge(); Spacer(Modifier.width(12.dp)) }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "A full duel with the real card rules. Play against the bot, or watch two bots play each other.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DuelMode.entries.forEach { m ->
                    FilterChip(setup.mode == m, { vm.updateDuelSetup { it.copy(mode = m) } }, { Text(m.label) })
                }
            }
            DeckField(if (human) "Your deck" else "Deck 1 (bottom)", choices.firstOrNull { it.key == setup.deck1 }, choices) { c ->
                vm.updateDuelSetup { it.copy(deck1 = c.key) }
            }
            DeckField(if (human) "Bot's deck" else "Deck 2 (top)", choices.firstOrNull { it.key == setup.deck2 }, choices) { c ->
                vm.updateDuelSetup { it.copy(deck2 = c.key) }
            }
            Text("Who goes first", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FirstPlayer.entries.forEach { f ->
                    val label = when (f) {
                        FirstPlayer.ME -> if (human) "You" else "Deck 1"
                        FirstPlayer.THEM -> if (human) "Bot" else "Deck 2"
                        FirstPlayer.RANDOM -> "Random"
                    }
                    FilterChip(setup.first == f, { vm.updateDuelSetup { it.copy(first = f) } }, { Text(label) })
                }
            }
            if (!human) {
                LevelPicker("Bot 1", setup.level1) { l -> vm.updateDuelSetup { it.copy(level1 = l) } }
            }
            LevelPicker(if (human) "Bot" else "Bot 2", setup.level2) { l -> vm.updateDuelSetup { it.copy(level2 = l) } }
            Text(
                "Easy plays by simple priorities. Normal and Strong plan each turn by searching ahead " +
                    "(Strong searches longer). The bot knows every card in the game, including your hand.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Delay between bot moves: ${"%.1f".format(setup.delayMs / 1000.0)} s",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = setup.delayMs.toFloat(),
                onValueChange = { v -> vm.updateDuelSetup { it.copy(delayMs = (v / 100).toLong() * 100) } },
                valueRange = 0f..3000f,
            )
            if (!human) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show both hands and set cards", modifier = Modifier.weight(1f))
                Switch(setup.reveal, { on -> vm.updateDuelSetup { it.copy(reveal = on) } })
            }
            ui.duel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { vm.startDuel() }, modifier = Modifier.fillMaxWidth()) { Text("Start duel") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LevelPicker(who: String, level: BotLevel, onPick: (BotLevel) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$who level", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BotLevel.entries.forEach { l -> FilterChip(level == l, { onPick(l) }, { Text(l.label) }) }
        }
    }
}

// ---------------- Board ----------------

@Composable
private fun DuelBoardScreen(d: DuelUi, vm: AppViewModel, onOpen: (List<Long>, Int) -> Unit) {
    val s = d.state
    val top = 1 - d.bottom
    val reveal = d.human < 0 && d.setup.reveal
    var pile by remember { mutableStateOf<Pair<String, List<DuelCard>>?>(null) }
    var showLog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Header(if (s == null) "Duel" else "Turn ${s.turn} · ${s.phase}", onBack = { vm.back() }) {
            if (d.human < 0 || s?.decision?.player != d.human) {
                IconButton(onClick = { vm.pauseDuel(!d.paused) }) {
                    Icon(if (d.paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (d.paused) "Resume" else "Pause")
                }
                if (d.paused) IconButton(onClick = { vm.stepDuel() }) { Icon(Icons.Default.SkipNext, "One bot move") }
            }
            IconButton(onClick = { vm.stopDuel() }) { Icon(Icons.Default.Stop, "End duel") }
        }
        if (s == null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (d.starting) "Shuffling and drawing…" else (d.error ?: ""))
                if (d.starting) LinearProgressIndicator(Modifier.fillMaxWidth())
                d.error?.let { OutlinedButton(onClick = { vm.stopDuel() }) { Text("Back to setup") } }
            }
            return@Column
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            PlayerBar(d.names[top], s.players[top], active = s.turnPlayer == top, onPile = { t, l -> pile = t to l })
            HandRow(vm, s.players[top].hand, faceUp = reveal, onOpen)
            Spacer(Modifier.height(4.dp))
            // Opponent's zones are mirrored: their zone 0 is on our right.
            ZoneRow(vm, s.players[top].spells.take(5).reversed(), side = s.players[top].spells.getOrNull(5), reveal, opponent = true, onOpen)
            ZoneRow(vm, s.players[top].monsters.take(5).reversed(), side = null, reveal, opponent = true, onOpen)
            ExtraZones(vm, s, d.bottom, reveal, onOpen)
            ZoneRow(vm, s.players[d.bottom].monsters.take(5), side = null, reveal = true, opponent = false, onOpen)
            ZoneRow(vm, s.players[d.bottom].spells.take(5), side = s.players[d.bottom].spells.getOrNull(5), reveal = true, opponent = false, onOpen)
            Spacer(Modifier.height(4.dp))
            HandRow(vm, s.players[d.bottom].hand, faceUp = d.human >= 0 || reveal, onOpen)
            PlayerBar(d.names[d.bottom], s.players[d.bottom], active = s.turnPlayer == d.bottom, onPile = { t, l -> pile = t to l })
            LogPanel(d.log, showLog) { showLog = !showLog }
            Spacer(Modifier.height(8.dp))
        }

        // Bottom panel: game over, the person's decision, or what the bot is doing.
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val decision = s.decision
                when {
                    s.ended -> GameOver(d, s, vm)
                    decision != null && decision.player == d.human -> DecisionPanel(vm, decision) { vm.chooseDuelOption(it) }
                    decision != null -> BotStatus(d, decision.player, vm)
                    else -> Text("…")
                }
            }
        }
    }

    pile?.let { (title, cards) ->
        AlertDialog(
            onDismissRequest = { pile = null },
            title = { Text("$title (${cards.size})") },
            text = {
                if (cards.isEmpty()) Text("Empty")
                else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(cards) { i, c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(cards.map { it.code }, i) }.padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CardThumbnail(vm, c.code, width = 30)
                            Spacer(Modifier.width(8.dp))
                            Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pile = null }) { Text("Close") } },
        )
    }
}

private val SLOT_WIDTH: Dp = 50.dp

@Composable
private fun PlayerBar(name: String, p: PlayerState, active: Boolean, onPile: (String, List<DuelCard>) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "LP ${p.lp}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = if (p.lp <= 2000) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        PileChip("Deck", p.deckCount) {}
        PileChip("Extra", p.extra.size) { onPile("$name · Extra Deck", p.extra) }
        PileChip("GY", p.graveyard.size) { onPile("$name · GY", p.graveyard) }
        PileChip("Ban", p.banished.size) { onPile("$name · banished", p.banished) }
    }
}

@Composable
private fun PileChip(label: String, count: Int, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 7.dp, vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$count", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HandRow(vm: AppViewModel, hand: List<DuelCard>, faceUp: Boolean, onOpen: (List<Long>, Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
        itemsIndexed(hand) { i, c ->
            if (faceUp) CardThumbnail(vm, c.code, width = 42) { onOpen(hand.map { it.code }, i) }
            else CardBack(42.dp)
        }
    }
}

@Composable
private fun CardBack(width: Dp) {
    Box(
        Modifier.width(width).aspectRatio(0.686f).clip(RoundedCornerShape(3.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF5B3A1E), Color(0xFF2E1B0E))))
            .border(1.dp, Color(0xFFB8860B), RoundedCornerShape(3.dp)),
    )
}

@Composable
private fun ZoneRow(
    vm: AppViewModel,
    zones: List<ZoneCard?>,
    side: ZoneCard?,
    reveal: Boolean,
    opponent: Boolean,
    onOpen: (List<Long>, Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val sideSlot: @Composable () -> Unit = { Slot(vm, side, reveal, onOpen, faint = true) }
        if (opponent) sideSlot() else Box(Modifier.width(SLOT_WIDTH))
        zones.forEach { z -> Slot(vm, z, reveal, onOpen) }
        if (!opponent) sideSlot() else Box(Modifier.width(SLOT_WIDTH))
    }
}

@Composable
private fun ExtraZones(vm: AppViewModel, s: DuelState, bottom: Int, reveal: Boolean, onOpen: (List<Long>, Int) -> Unit) {
    // Extra Monster Zones (5 and 6) are shared: show whichever player occupies each.
    val top = 1 - bottom
    val left = s.players[bottom].monsters.getOrNull(5) ?: s.players[top].monsters.getOrNull(6)
    val right = s.players[bottom].monsters.getOrNull(6) ?: s.players[top].monsters.getOrNull(5)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        Box(Modifier.width(SLOT_WIDTH))
        Box(Modifier.width(SLOT_WIDTH))
        Slot(vm, left, reveal, onOpen, faint = true)
        Box(Modifier.width(SLOT_WIDTH))
        Slot(vm, right, reveal, onOpen, faint = true)
        Box(Modifier.width(SLOT_WIDTH))
        Box(Modifier.width(SLOT_WIDTH))
    }
}

@Composable
private fun Slot(vm: AppViewModel, c: ZoneCard?, reveal: Boolean, onOpen: (List<Long>, Int) -> Unit, faint: Boolean = false) {
    Box(
        Modifier.width(SLOT_WIDTH).aspectRatio(0.73f)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (faint) 0.4f else 0.8f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (c != null) {
            val defense = c.isMonster && c.position != "atk"
            val canSee = !c.faceDown || reveal
            Box(Modifier.rotate(if (defense) 90f else 0f).padding(2.dp)) {
                if (canSee) {
                    AsyncImage(
                        model = vm.db.find(c.code)?.imageUrl(small = true) ?: "https://images.ygoprodeck.com/images/cards_small/${c.code}.jpg",
                        contentDescription = c.name,
                        contentScale = ContentScale.Crop,
                        alpha = if (c.faceDown) 0.55f else 1f,
                        modifier = Modifier.width(SLOT_WIDTH - 12.dp).aspectRatio(0.686f).clip(RoundedCornerShape(2.dp))
                            .clickable { onOpen(listOf(c.code), 0) },
                    )
                } else {
                    CardBack(SLOT_WIDTH - 12.dp)
                }
            }
            if (c.isMonster && !c.faceDown) {
                Text(
                    "${c.atk}" + if (c.materials > 0) " ·${c.materials}" else "",
                    fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).background(Color(0xAA000000), RoundedCornerShape(2.dp))
                        .padding(horizontal = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun LogPanel(log: List<String>, open: Boolean, onToggle: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
                Text("Duel log", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (open) "Show less" else "Show all", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            val lines = if (open) log else log.takeLast(6)
            if (open) {
                val state = rememberLazyListState()
                LaunchedEffect(log.size) { if (log.isNotEmpty()) state.scrollToItem(log.size - 1) }
                LazyColumn(Modifier.heightIn(max = 320.dp), state = state) {
                    items(lines) { LogLine(it) }
                }
            } else {
                lines.forEach { LogLine(it) }
            }
        }
    }
}

@Composable
private fun LogLine(line: String) {
    val turn = line.startsWith("—")
    Text(
        line,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (turn) FontWeight.Bold else FontWeight.Normal,
        color = if (line.startsWith("   ")) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun DecisionPanel(vm: AppViewModel, d: DuelDecision, onChoose: (Int) -> Unit) {
    Text(d.prompt, fontWeight = FontWeight.SemiBold)
    LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(d.options) { i, o ->
            val passive = o.kind in setOf("pass", "no", "end", "main2")
            Surface(
                color = if (passive) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().clickable { onChoose(i) },
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (o.code != 0L) {
                        CardThumbnail(vm, o.code, width = 28)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(o.label, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun BotStatus(d: DuelUi, player: Int, vm: AppViewModel) {
    val name = d.names.getOrElse(player) { "Bot" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                d.thinking -> "$name is thinking…"
                d.paused -> "Paused — tap ▶ to continue or ⏭ for one move"
                else -> "$name to move"
            },
            modifier = Modifier.weight(1f),
        )
        if (d.thinking) TextButton(onClick = { vm.hurryBot() }) { Text("Hurry") }
    }
    if (d.thinking) LinearProgressIndicator(Modifier.fillMaxWidth())
    d.state?.lastMove?.takeIf { it.isNotBlank() }?.let {
        Text("Last move: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GameOver(d: DuelUi, s: DuelState, vm: AppViewModel) {
    val text = when {
        s.winner == 0 || s.winner == 1 -> "${d.names[s.winner]} won${if (s.winner == d.human) "!" else ""}"
        else -> "The duel is a draw"
    }
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text("Turn ${s.turn} · LP ${s.players[d.bottom].lp} – ${s.players[1 - d.bottom].lp}", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.startDuel() }) { Text("Rematch") }
        OutlinedButton(onClick = { vm.stopDuel() }) { Text("Change setup") }
    }
}
