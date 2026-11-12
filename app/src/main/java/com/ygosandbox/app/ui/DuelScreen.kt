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
import com.ygosandbox.app.play.LogEntry
import com.ygoengine.DuelMove
import com.ygoengine.DuelPlace
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

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
    val spots = remember { BoardSpots() }
    val colors = playerColors(d.bottom)

    Box(Modifier.fillMaxSize()) {
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
            PlayerBar(d.names[top], s.players[top], top, colors.of(top), spots, active = s.turnPlayer == top, onPile = { t, l -> pile = t to l })
            HandRow(vm, s.players[top].hand, top, spots, faceUp = reveal, onOpen)
            Spacer(Modifier.height(4.dp))
            // Opponent's zones are mirrored: their zone 0 is on our right.
            ZoneRow(vm, s.players[top].spells.take(5).reversed(), listOf(4, 3, 2, 1, 0), side = s.players[top].spells.getOrNull(5),
                top, 's', spots, reveal, opponent = true, onOpen)
            ZoneRow(vm, s.players[top].monsters.take(5).reversed(), listOf(4, 3, 2, 1, 0), side = null,
                top, 'm', spots, reveal, opponent = true, onOpen)
            ExtraZones(vm, s, d.bottom, spots, reveal, onOpen)
            ZoneRow(vm, s.players[d.bottom].monsters.take(5), listOf(0, 1, 2, 3, 4), side = null,
                d.bottom, 'm', spots, reveal = true, opponent = false, onOpen)
            ZoneRow(vm, s.players[d.bottom].spells.take(5), listOf(0, 1, 2, 3, 4), side = s.players[d.bottom].spells.getOrNull(5),
                d.bottom, 's', spots, reveal = true, opponent = false, onOpen)
            Spacer(Modifier.height(4.dp))
            HandRow(vm, s.players[d.bottom].hand, d.bottom, spots, faceUp = d.human >= 0 || reveal, onOpen)
            PlayerBar(d.names[d.bottom], s.players[d.bottom], d.bottom, colors.of(d.bottom), spots, active = s.turnPlayer == d.bottom, onPile = { t, l -> pile = t to l })
            LogPanel(d.log, d.names, colors, showLog) { showLog = !showLog }
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
    // Cards flying between zones, drawn above the board.
    MoveOverlay(d, vm, spots, canSee = { p -> reveal || p == d.human })
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
private fun PlayerBar(
    name: String,
    p: PlayerState,
    player: Int,
    color: Color,
    spots: BoardSpots,
    active: Boolean,
    onPile: (String, List<DuelCard>) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
                Spacer(Modifier.width(6.dp))
                Text(
                    name + if (active) " · turn" else "", fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = color,
                )
            }
            Text(
                "LP ${p.lp}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = if (p.lp <= 2000) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        PileChip("Deck", p.deckCount, spots.at("deck/$player")) {}
        PileChip("Extra", p.extra.size, spots.at("extra/$player")) { onPile("$name · Extra Deck", p.extra) }
        PileChip("GY", p.graveyard.size, spots.at("gy/$player")) { onPile("$name · GY", p.graveyard) }
        PileChip("Ban", p.banished.size, spots.at("ban/$player")) { onPile("$name · banished", p.banished) }
    }
}

@Composable
private fun PileChip(label: String, count: Int, spot: Modifier, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(8.dp),
        modifier = spot.clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 7.dp, vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$count", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HandRow(vm: AppViewModel, hand: List<DuelCard>, player: Int, spots: BoardSpots, faceUp: Boolean, onOpen: (List<Long>, Int) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).then(spots.at("hand/$player")),
        horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        itemsIndexed(hand) { i, c ->
            Box(spots.at("hc/$player/$i")) {
                if (faceUp) CardThumbnail(vm, c.code, width = 42) { onOpen(hand.map { it.code }, i) }
                else CardBack(42.dp)
            }
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
    seqs: List<Int>,
    side: ZoneCard?,
    player: Int,
    kind: Char,
    spots: BoardSpots,
    reveal: Boolean,
    opponent: Boolean,
    onOpen: (List<Long>, Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The side slot of the Spell & Trap row is the Field Zone (slot 5).
        val sideSpot = if (kind == 's') spots.at("s/$player/5") else Modifier
        val sideSlot: @Composable () -> Unit = { Slot(vm, side, reveal, onOpen, faint = true, spot = sideSpot) }
        if (opponent) sideSlot() else Box(Modifier.width(SLOT_WIDTH))
        zones.forEachIndexed { i, z -> Slot(vm, z, reveal, onOpen, spot = spots.at("$kind/$player/${seqs[i]}")) }
        if (!opponent) sideSlot() else Box(Modifier.width(SLOT_WIDTH))
    }
}

@Composable
private fun ExtraZones(vm: AppViewModel, s: DuelState, bottom: Int, spots: BoardSpots, reveal: Boolean, onOpen: (List<Long>, Int) -> Unit) {
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
        Slot(vm, left, reveal, onOpen, faint = true, spot = spots.at("m/$bottom/5", "m/$top/6"))
        Box(Modifier.width(SLOT_WIDTH))
        Slot(vm, right, reveal, onOpen, faint = true, spot = spots.at("m/$bottom/6", "m/$top/5"))
        Box(Modifier.width(SLOT_WIDTH))
        Box(Modifier.width(SLOT_WIDTH))
    }
}

@Composable
private fun Slot(
    vm: AppViewModel,
    c: ZoneCard?,
    reveal: Boolean,
    onOpen: (List<Long>, Int) -> Unit,
    faint: Boolean = false,
    spot: Modifier = Modifier,
) {
    Box(
        spot.width(SLOT_WIDTH).aspectRatio(0.73f)
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
private fun LogPanel(log: List<LogEntry>, names: List<String>, colors: PlayerColors, open: Boolean, onToggle: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
                Text("Duel log", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (open) "Show less" else "Show all", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            // Which colour is which player.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                names.forEachIndexed { p, n ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(colors.of(p)))
                        Spacer(Modifier.width(4.dp))
                        Text(n, style = MaterialTheme.typography.labelSmall, color = colors.of(p), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val lines = if (open) log else log.takeLast(6)
            if (open) {
                val state = rememberLazyListState()
                LaunchedEffect(log.size) { if (log.isNotEmpty()) state.scrollToItem(log.size - 1) }
                LazyColumn(Modifier.heightIn(max = 320.dp), state = state) {
                    items(lines) { LogLine(it, colors) }
                }
            } else {
                lines.forEach { LogLine(it, colors) }
            }
        }
    }
}

@Composable
private fun LogLine(entry: LogEntry, colors: PlayerColors) {
    val line = entry.text
    val turn = line.startsWith("—")
    val detail = line.startsWith("   ")
    // Phases have no actor of their own; show them neutral so player actions stand out.
    val phase = line in PHASES
    val color = when {
        entry.player < 0 || phase -> MaterialTheme.colorScheme.onSurfaceVariant
        detail -> colors.of(entry.player).copy(alpha = 0.75f)
        else -> colors.of(entry.player)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(3.dp).height(14.dp)
                .background(if (entry.player < 0 || phase) Color.Transparent else colors.of(entry.player).copy(alpha = if (detail) 0.4f else 1f)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            line.trimStart(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (turn) FontWeight.Bold else FontWeight.Normal,
            color = color,
            modifier = if (detail) Modifier.padding(start = 10.dp) else Modifier,
        )
    }
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

private val PHASES = setOf("Draw Phase", "Standby Phase", "Main Phase 1", "Battle Phase", "Main Phase 2", "End Phase")

// ---------------- Player colours ----------------

/** Each player's colour in the log and on the board: blue for the bottom player, orange for the top. */
class PlayerColors(private val bottom: Int, private val bottomColor: Color, private val topColor: Color) {
    fun of(player: Int): Color = if (player == bottom) bottomColor else topColor
}

@Composable
private fun playerColors(bottom: Int): PlayerColors =
    if (isSystemInDarkTheme()) PlayerColors(bottom, Color(0xFF64B5F6), Color(0xFFFF8A65))
    else PlayerColors(bottom, Color(0xFF1565C0), Color(0xFFD84315))

// ---------------- Card movement animation ----------------

/** Where each zone, pile and hand card is on screen, so moving cards know where to fly. */
class BoardSpots {
    private val rects = HashMap<String, Rect>()

    /** Remembers this element's place under [keys] (one element can stand for several places). */
    fun at(vararg keys: String): Modifier = Modifier.onGloballyPositioned { c ->
        val r = c.boundsInRoot()
        keys.forEach { rects[it] = r }
    }

    fun find(p: DuelPlace): Rect? {
        val loc = p.location
        return when {
            loc and (DuelPlace.MONSTER_ZONE or DuelPlace.MATERIAL) != 0 -> rects["m/${p.player}/${p.sequence}"]
            loc and DuelPlace.SPELL_ZONE != 0 -> {
                // Pendulum Zones (6, 7) sit on the outer Spell & Trap Zones.
                val seq = when (p.sequence) { 6 -> 0; 7 -> 4; else -> p.sequence }
                rects["s/${p.player}/$seq"]
            }
            loc and DuelPlace.HAND != 0 -> rects["hc/${p.player}/${p.sequence}"]?.takeIf { it.width > 0f } ?: rects["hand/${p.player}"]
            loc and DuelPlace.DECK != 0 -> rects["deck/${p.player}"]
            loc and DuelPlace.EXTRA != 0 -> rects["extra/${p.player}"]
            loc and DuelPlace.GRAVEYARD != 0 -> rects["gy/${p.player}"]
            loc and DuelPlace.BANISHED != 0 -> rects["ban/${p.player}"]
            else -> null
        }
    }
}

private class Flight(val id: Long, val code: Long, val showBack: Boolean, val from: Rect, val to: Rect, val delayMs: Long)

/** Draws the cards that moved in the latest update flying from their old place to their new one. */
@Composable
private fun MoveOverlay(d: DuelUi, vm: AppViewModel, spots: BoardSpots, canSee: (Int) -> Boolean) {
    val flights = remember { mutableStateListOf<Flight>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var nextId by remember { mutableStateOf(0L) }
    LaunchedEffect(d.moveBatch) {
        if (d.moveBatch == 0) return@LaunchedEffect
        // Let the new board lay out first, so every place has its current position.
        delay(16)
        // A long combo in one update: animate the last moves only, a little apart.
        d.moves.takeLast(MAX_FLIGHTS).forEachIndexed { i, m ->
            val from = spots.find(m.from) ?: return@forEachIndexed
            val to = spots.find(m.to) ?: return@forEachIndexed
            if (from == to) return@forEachIndexed
            flights += Flight(nextId++, m.code, hidden(m, canSee), from, to, i * STAGGER_MS)
        }
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        flights.toList().forEach { f ->
            key(f.id) { FlyingCard(f, origin, vm) { flights.remove(f) } }
        }
    }
}

private const val MAX_FLIGHTS = 12
private const val STAGGER_MS = 110L
private const val FLIGHT_MS = 480

/**
 * Whether the moving card should be shown face-down: it lands face-down where the viewer can't
 * see it, or it moves between hidden places (a hidden player's Deck and hand).
 */
private fun hidden(m: DuelMove, canSee: (Int) -> Boolean): Boolean {
    if (m.code == 0L) return true
    val field = DuelPlace.MONSTER_ZONE or DuelPlace.SPELL_ZONE
    val secret = DuelPlace.DECK or DuelPlace.HAND
    if (m.to.location and field != 0 && m.to.faceDown && !canSee(m.to.player)) return true
    return m.from.location and secret != 0 && m.to.location and secret != 0 && !canSee(m.to.player)
}

@Composable
private fun FlyingCard(f: Flight, origin: Offset, vm: AppViewModel, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(f.id) {
        delay(f.delayMs)
        started = true
        progress.animateTo(1f, tween(FLIGHT_MS, easing = FastOutSlowInEasing))
        onDone()
    }
    if (!started) return
    val t = progress.value
    val center = androidx.compose.ui.geometry.lerp(f.from.center, f.to.center, t) - origin
    val width = 40.dp
    val height = width / 0.686f
    Box(
        Modifier
            .offset {
                IntOffset(
                    (center.x - width.toPx() / 2).roundToInt(),
                    (center.y - height.toPx() / 2).roundToInt(),
                )
            }
            .size(width, height)
            .graphicsLayer {
                // Lift the card a little in the middle of its flight.
                val lift = 1f + 0.18f * sin(PI * t).toFloat()
                scaleX = lift
                scaleY = lift
                shadowElevation = 12f * sin(PI * t).toFloat()
                alpha = if (t > 0.92f) (1f - t) / 0.08f else 1f
            },
    ) {
        if (f.showBack) {
            CardBack(width)
        } else {
            AsyncImage(
                model = vm.db.find(f.code)?.imageUrl(small = true) ?: "https://images.ygoprodeck.com/images/cards_small/${f.code}.jpg",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp))
                    .border(1.dp, Color(0xFFB8860B), RoundedCornerShape(3.dp)),
            )
        }
    }
}
