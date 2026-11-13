@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ygosandbox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import coil3.compose.AsyncImage
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
import com.ygoengine.DuelActivation
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
import kotlinx.coroutines.async
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
    // Places with a card still flying in: they keep their old look until it lands.
    val pending = remember(d.moveBatch) { Pending.of(d.moves) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = maxWidth >= 1000.dp && maxHeight >= 560.dp
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

        val board: @Composable (slot: Dp, hand: Int) -> Unit = { slot, hand ->
            PlayerBar(d.names[top], s.players[top], colors.of(top), active = s.turnPlayer == top)
            HandRow(vm, s.players[top].hand, top, spots, pending, faceUp = reveal, onOpen, hand)
            Spacer(Modifier.height(4.dp))
            Mat(vm, s, d.bottom, spots, pending, reveal, onOpen, onPile = { t, l -> pile = t to l }, names = d.names, maxSlot = slot)
            Spacer(Modifier.height(4.dp))
            HandRow(vm, s.players[d.bottom].hand, d.bottom, spots, pending, faceUp = d.human >= 0 || reveal, onOpen, hand)
            PlayerBar(d.names[d.bottom], s.players[d.bottom], colors.of(d.bottom), active = s.turnPlayer == d.bottom)
        }
        if (wide) {
            // Desktop window: the mat as big as the height allows on the left; log and choices on the right.
            Row(Modifier.weight(1f).fillMaxWidth()) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    // 5 mat rows (slot / 0.73 each) + 2 hands (0.8 slot wide cards) + bars and gaps.
                    val slot = minOf((maxHeight - 132.dp) / 9.2f, (maxWidth - 32.dp - MAT_GAP * 6) / 7).coerceIn(48.dp, 104.dp)
                    val hand = (slot.value * 0.8f).toInt().coerceAtLeast(42)
                    Column(
                        Modifier.width(slot * 7 + MAT_GAP * 6 + 16.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp),
                    ) { board(slot, hand) }
                }
                Column(Modifier.width(380.dp).fillMaxHeight().padding(end = 12.dp, bottom = 12.dp)) {
                    LogPanel(d.log, d.names, colors, open = true, onToggle = {}, fill = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.height(8.dp))
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp, shape = RoundedCornerShape(12.dp)) {
                        BottomPanel(d, s, vm)
                    }
                }
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                board(58.dp, 42)
                LogPanel(d.log, d.names, colors, showLog) { showLog = !showLog }
                Spacer(Modifier.height(8.dp))
            }

            // Bottom panel: game over, the person's decision, or what the bot is doing.
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) { BottomPanel(d, s, vm) }
        }
    }
    // Cards flying between zones, drawn above the board.
    MoveOverlay(d, vm, spots, pending, colors, canSee = { p -> reveal || p == d.human })
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

/** Game over, the person's decision, or what the bot is doing. */
@Composable
private fun BottomPanel(d: DuelUi, s: DuelState, vm: AppViewModel) {
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

private val MAT_GAP = 3.dp

@Composable
private fun PlayerBar(name: String, p: PlayerState, color: Color, active: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Text(
            name + if (active) " · turn" else "", fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = color, modifier = Modifier.weight(1f),
        )
        Text(
            "LP ${p.lp}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = if (p.lp <= 2000) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun HandRow(
    vm: AppViewModel,
    hand: List<DuelCard>,
    player: Int,
    spots: BoardSpots,
    pending: Pending,
    faceUp: Boolean,
    onOpen: (List<Long>, Int) -> Unit,
    cardWidth: Int = 42,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().heightIn(min = (cardWidth / 0.686f + 4).dp).then(spots.at("hand/$player")),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        itemsIndexed(hand) { i, c ->
            val key = "hc/$player/$i"
            // A card still flying into the hand keeps its space but isn't drawn yet.
            Box(spots.at(key).graphicsLayer { alpha = if (pending.has(key)) 0f else 1f }) {
                if (faceUp) CardThumbnail(vm, c.code, width = cardWidth) { onOpen(hand.map { it.code }, i) }
                else CardBack(cardWidth.dp)
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

/**
 * The playmat, as in Master Rule: for each player a Monster row (Field Zone, 5 Monster Zones, GY)
 * and a back row (Extra Deck, 5 Spell & Trap Zones, Deck), with the 2 shared Extra Monster Zones
 * in the middle and each player's banished pile beside their GY. The top player is mirrored.
 */
@Composable
private fun Mat(
    vm: AppViewModel,
    s: DuelState,
    bottom: Int,
    spots: BoardSpots,
    pending: Pending,
    reveal: Boolean,
    onOpen: (List<Long>, Int) -> Unit,
    onPile: (String, List<DuelCard>) -> Unit,
    names: List<String>,
    maxSlot: Dp = 58.dp,
) {
    val top = 1 - bottom
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = MAT_GAP
        val slot = ((maxWidth - gap * 6) / 7).coerceAtMost(maxSlot)
        spots.slotWidth = slot
        val ctx = MatCtx(vm, s, spots, pending, reveal, slot, onOpen, onPile, names)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
            // Top player, seen from across the table.
            MatRow(gap) {
                ctx.Pile(top, PileKind.DECK)
                for (seq in 4 downTo 0) ctx.Zone(top, 's', seq, faceUpOnly = false)
                ctx.Pile(top, PileKind.EXTRA)
            }
            MatRow(gap) {
                ctx.Pile(top, PileKind.GY)
                for (seq in 4 downTo 0) ctx.Zone(top, 'm', seq)
                ctx.Zone(top, 's', 5, label = "Field")
            }
            // Middle: banished piles and the shared Extra Monster Zones.
            MatRow(gap) {
                ctx.Pile(top, PileKind.BANISHED)
                Box(Modifier.width(slot))
                ctx.ExtraMonsterZone(left = true, bottom = bottom)
                Box(Modifier.width(slot))
                ctx.ExtraMonsterZone(left = false, bottom = bottom)
                Box(Modifier.width(slot))
                ctx.Pile(bottom, PileKind.BANISHED)
            }
            // Bottom player.
            MatRow(gap) {
                ctx.Zone(bottom, 's', 5, label = "Field")
                for (seq in 0..4) ctx.Zone(bottom, 'm', seq)
                ctx.Pile(bottom, PileKind.GY)
            }
            MatRow(gap) {
                ctx.Pile(bottom, PileKind.EXTRA)
                for (seq in 0..4) ctx.Zone(bottom, 's', seq, faceUpOnly = false)
                ctx.Pile(bottom, PileKind.DECK)
            }
        }
    }
}

@Composable
private fun MatRow(gap: Dp, content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) { content() }
}

private enum class PileKind(val label: String, val key: String) {
    DECK("Deck", "deck"), EXTRA("Extra", "extra"), GY("GY", "gy"), BANISHED("Banished", "ban"),
}

/** Everything a mat slot needs, so the layout above stays readable. */
private class MatCtx(
    val vm: AppViewModel,
    val s: DuelState,
    val spots: BoardSpots,
    val pending: Pending,
    val reveal: Boolean,
    val slot: Dp,
    val onOpen: (List<Long>, Int) -> Unit,
    val onPile: (String, List<DuelCard>) -> Unit,
    val names: List<String>,
) {
    fun card(player: Int, kind: Char, seq: Int): ZoneCard? {
        val p = s.players[player]
        return if (kind == 'm') p.monsters.getOrNull(seq)
        // Pendulum Zones (6, 7) are shown on the outer Spell & Trap Zones.
        else p.spells.getOrNull(seq) ?: when (seq) { 0 -> p.spells.getOrNull(6); 4 -> p.spells.getOrNull(7); else -> null }
    }

    @Composable
    fun Zone(player: Int, kind: Char, seq: Int, label: String? = null, faceUpOnly: Boolean = true) {
        val key = "$kind/$player/$seq"
        val c = if (pending.has(key)) null else card(player, kind, seq)
        ZoneSlot(vm, c, slot, reveal, label, spots.at(key), onOpen)
    }

    @Composable
    fun ExtraMonsterZone(left: Boolean, bottom: Int) {
        // Zone 5 is the bottom player's left EMZ and the top player's right one (and 6 the other way round).
        val top = 1 - bottom
        val mine = if (left) 5 else 6
        val theirs = if (left) 6 else 5
        val keys = arrayOf("m/$bottom/$mine", "m/$top/$theirs")
        val c = when {
            keys.any { pending.has(it) } -> null
            else -> s.players[bottom].monsters.getOrNull(mine) ?: s.players[top].monsters.getOrNull(theirs)
        }
        ZoneSlot(vm, c, slot, reveal, "EMZ", spots.at(*keys), onOpen)
    }

    @Composable
    fun Pile(player: Int, kind: PileKind) {
        val p = s.players[player]
        val key = "${kind.key}/$player"
        val waiting = pending.count(key)
        val cards = when (kind) {
            PileKind.EXTRA -> p.extra
            PileKind.GY -> p.graveyard
            PileKind.BANISHED -> p.banished
            PileKind.DECK -> emptyList()
        }
        val total = if (kind == PileKind.DECK) p.deckCount else cards.size
        // Cards still flying here aren't counted yet; the pile shows what was on top before them.
        val count = (total - waiting).coerceAtLeast(0)
        val visible = cards.dropLast(waiting.coerceAtMost(cards.size))
        val topCard = if (kind == PileKind.GY || kind == PileKind.BANISHED) visible.lastOrNull() else null
        Box(
            spots.at(key).width(slot).aspectRatio(0.73f)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .clickable(enabled = kind != PileKind.DECK) { onPile("${names[player]} · ${kind.label}", visible.reversed()) },
            contentAlignment = Alignment.Center,
        ) {
            when {
                count == 0 -> SlotLabel(kind.label, slot)
                topCard != null -> AsyncImage(
                    model = vm.db.find(topCard.code)?.imageUrl(small = true)
                        ?: "https://images.ygoprodeck.com/images/cards_small/${topCard.code}.jpg",
                    contentDescription = topCard.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(slot - 8.dp).aspectRatio(0.686f).clip(RoundedCornerShape(2.dp)),
                )
                else -> CardBack(slot - 8.dp)
            }
            if (count > 0) Text(
                "$count",
                fontSize = labelSize(slot, 10f), fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp),
            )
        }
    }
}

/** Text on a mat slot grows with the slot: [phone] sp at the phone's 58 dp, up to 1.6× on a desktop. */
private fun labelSize(slot: Dp, phone: Float) = (phone * (slot.value / 58f).coerceIn(1f, 1.6f)).sp

@Composable
private fun SlotLabel(text: String, slot: Dp) {
    Text(
        text, fontSize = labelSize(slot, 8f), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        textAlign = TextAlign.Center, maxLines = 1,
    )
}

@Composable
private fun ZoneSlot(
    vm: AppViewModel,
    c: ZoneCard?,
    slot: Dp,
    reveal: Boolean,
    label: String?,
    spot: Modifier,
    onOpen: (List<Long>, Int) -> Unit,
) {
    Box(
        spot.width(slot).aspectRatio(0.73f)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (label != null) 0.45f else 0.8f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (c == null) {
            if (label != null) SlotLabel(label, slot)
            return@Box
        }
        val defense = c.isMonster && c.position != "atk"
        val canSee = !c.faceDown || reveal
        Box(Modifier.rotate(if (defense) 90f else 0f).padding(2.dp)) {
            if (canSee) {
                AsyncImage(
                    model = vm.db.find(c.code)?.imageUrl(small = true) ?: "https://images.ygoprodeck.com/images/cards_small/${c.code}.jpg",
                    contentDescription = c.name,
                    contentScale = ContentScale.Crop,
                    alpha = if (c.faceDown) 0.55f else 1f,
                    modifier = Modifier.width(slot - 10.dp).aspectRatio(0.686f).clip(RoundedCornerShape(2.dp))
                        .clickable { onOpen(listOf(c.code), 0) },
                )
            } else {
                CardBack(slot - 10.dp)
            }
        }
        if (c.isMonster && !c.faceDown) {
            Text(
                (if (defense) "${c.def}" else "${c.atk}") + if (c.materials > 0) " ·${c.materials}" else "",
                fontSize = labelSize(slot, 9f), fontWeight = FontWeight.Bold,
                color = if (defense) Color(0xFF90CAF9) else Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).background(Color(0xAA000000), RoundedCornerShape(2.dp))
                    .padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun LogPanel(
    log: List<LogEntry>,
    names: List<String>,
    colors: PlayerColors,
    open: Boolean,
    onToggle: () -> Unit,
    /** Desktop side panel: the whole log, filling the panel's height. */
    fill: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(if (fill) Modifier.fillMaxSize().padding(10.dp) else Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable(enabled = !fill, onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
                Text("Duel log", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (!fill) Text(if (open) "Show less" else "Show all", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
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
                LazyColumn(if (fill) Modifier.weight(1f) else Modifier.heightIn(max = 320.dp), state = state) {
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

/** Key of a place on the board, as used by [BoardSpots] and [Pending]; null for Xyz materials. */
private fun placeKey(p: DuelPlace): String? {
    val loc = p.location
    return when {
        loc and DuelPlace.MATERIAL != 0 -> null
        loc and DuelPlace.MONSTER_ZONE != 0 -> "m/${p.player}/${p.sequence}"
        // Pendulum Zones (6, 7) sit on the outer Spell & Trap Zones.
        loc and DuelPlace.SPELL_ZONE != 0 -> "s/${p.player}/${when (p.sequence) { 6 -> 0; 7 -> 4; else -> p.sequence }}"
        loc and DuelPlace.HAND != 0 -> "hc/${p.player}/${p.sequence}"
        loc and DuelPlace.DECK != 0 -> "deck/${p.player}"
        loc and DuelPlace.EXTRA != 0 -> "extra/${p.player}"
        loc and DuelPlace.GRAVEYARD != 0 -> "gy/${p.player}"
        loc and DuelPlace.BANISHED != 0 -> "ban/${p.player}"
        else -> null
    }
}

/** Where each zone, pile and hand card is on screen, so moving cards know where to fly. */
class BoardSpots {
    private val rects = HashMap<String, Rect>()
    /** Width of a mat slot, so flying cards are the size of the cards on the field. */
    var slotWidth: Dp = 46.dp

    /** Remembers this element's place under [keys] (one element can stand for several places). */
    fun at(vararg keys: String): Modifier = Modifier.onGloballyPositioned { c ->
        val r = c.boundsInRoot()
        keys.forEach { rects[it] = r }
    }

    fun find(p: DuelPlace): Rect? {
        // Xyz materials fly to the monster they're attached to.
        if (p.location and DuelPlace.MATERIAL != 0) return rects["m/${p.player}/${p.sequence}"]
        val key = placeKey(p) ?: return null
        if (p.location and DuelPlace.HAND != 0) return rects[key]?.takeIf { it.width > 0f } ?: rects["hand/${p.player}"]
        return rects[key]
    }
}

/** One planned flight: the move, and whether its destination waits for it (see [Pending]). */
private class Plan(val move: DuelMove, val waitKey: String?)

/**
 * Moves of the latest update that will be animated. A destination waits (shows its old look) only
 * if the card is still there at the end of the update — not if a later move takes it away again.
 */
private fun plan(moves: List<DuelMove>): List<Plan> {
    val shown = moves.takeLast(MAX_FLIGHTS)
    return shown.mapIndexed { i, m ->
        val to = placeKey(m.to)
        val leavesAgain = to != null && shown.drop(i + 1).any { placeKey(it.from) == to }
        Plan(m, if (leavesAgain) null else to)
    }
}

/** Places with cards still flying in, and how many. Read by the board, emptied as cards land. */
class Pending private constructor(private val counts: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Int>) {
    fun has(key: String) = (counts[key] ?: 0) > 0
    fun count(key: String) = counts[key] ?: 0
    fun land(key: String?) {
        if (key == null) return
        val n = (counts[key] ?: 0) - 1
        if (n <= 0) counts.remove(key) else counts[key] = n
    }

    companion object {
        fun of(moves: List<DuelMove>): Pending {
            val map = androidx.compose.runtime.mutableStateMapOf<String, Int>()
            plan(moves).forEach { p -> p.waitKey?.let { map[it] = (map[it] ?: 0) + 1 } }
            return Pending(map)
        }
    }
}

private class Flight(
    val id: Long,
    val code: Long,
    val showBack: Boolean,
    val from: Rect,
    val to: Rect,
    val delayMs: Long,
    val waitKey: String?,
)

/** Draws the cards that moved in the latest update flying from their old place to their new one. */
@Composable
private fun MoveOverlay(
    d: DuelUi,
    vm: AppViewModel,
    spots: BoardSpots,
    pending: Pending,
    colors: PlayerColors,
    canSee: (Int) -> Boolean,
) {
    val flights = remember { mutableStateListOf<Flight>() }
    val effects = remember { mutableStateListOf<ActivationFx>() }
    // Activations still showing from the previous update: new ones wait for them instead of cutting them off.
    var busyUntil by remember { mutableStateOf(0L) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var nextId by remember { mutableStateOf(0L) }
    LaunchedEffect(d.moveBatch) {
        flights.clear()
        if (d.moveBatch == 0) return@LaunchedEffect
        // Let the new board lay out first, so every place has its current position.
        delay(16)
        val now = System.currentTimeMillis()
        val wait = (busyUntil - now).coerceAtLeast(0L)
        val times = timeline(d.moves, d.activations)
        times.activations.forEach { (a, start) ->
            effects += ActivationFx(nextId++, a.code, a.place.player, spots.find(a.place), wait + start)
        }
        // Tell the duel when this update's animations are over, so the next bot move waits for them.
        val end = wait + times.endMs + PRELOAD_SLACK_MS
        busyUntil = now + wait + (times.activations.maxOfOrNull { it.second + ACTIVATION_MS } ?: 0L)
        vm.duelAnimationsUntil = now + end
        plan(d.moves).forEachIndexed { i, p ->
            val from = spots.find(p.move.from)
            val to = spots.find(p.move.to)
            if (from == null || to == null || from == to) {
                pending.land(p.waitKey) // nothing to animate: show the card right away
                return@forEachIndexed
            }
            flights += Flight(nextId++, p.move.code, hidden(p.move, canSee), from, to, wait + times.moveStarts[i], p.waitKey)
        }
    }
    val width = spots.slotWidth - 8.dp
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        flights.toList().forEach { f ->
            key(f.id) {
                FlyingCard(f, origin, width, vm) {
                    pending.land(f.waitKey)
                    flights.remove(f)
                }
            }
        }
        effects.toList().forEach { fx ->
            key(fx.id) { ActivationEffect(fx, origin, colors.of(fx.player), vm) { effects.remove(fx) } }
        }
    }
}

// ---------------- Activation effect ----------------

private class ActivationFx(val id: Long, val code: Long, val player: Int, val spot: Rect?, val delayMs: Long)

/** When each move and activation of an update starts, so they play in the order they happened. */
private class Timeline(val moveStarts: LongArray, val activations: List<Pair<DuelActivation, Long>>) {
    /** When the last animation of the update ends. */
    val endMs: Long
        get() = maxOf(
            moveStarts.maxOrNull()?.plus(FLIGHT_MS) ?: 0L,
            activations.maxOfOrNull { it.second + ACTIVATION_MS } ?: 0L,
        )
}

/**
 * Lays the update's moves and activations on one timeline: moves start a little apart, and an
 * activation waits for the cards before it to land, then holds the next moves until it has shown.
 */
private fun timeline(moves: List<DuelMove>, activations: List<DuelActivation>): Timeline {
    val shown = minOf(moves.size, MAX_FLIGHTS)
    val offset = moves.size - shown
    val acts = activations.takeLast(MAX_ACTIVATIONS)
    val starts = LongArray(shown)
    val out = ArrayList<Pair<DuelActivation, Long>>()
    var cursor = 0L
    var lastEnd = 0L
    var ai = 0
    for (i in 0..moves.size) {
        while (ai < acts.size && acts[ai].afterMoves <= i) {
            val start = maxOf(cursor, lastEnd)
            out += acts[ai++] to start
            cursor = start + ACTIVATION_GAP_MS
            lastEnd = cursor
        }
        if (i == moves.size) break
        if (i >= offset) {
            starts[i - offset] = cursor
            lastEnd = maxOf(lastEnd, cursor + FLIGHT_MS)
            cursor += STAGGER_MS
        }
    }
    return Timeline(starts, out)
}

private const val MAX_ACTIVATIONS = 6
/** Extra time an activation may wait for its card picture to download. */
private const val PRELOAD_SLACK_MS = 600L
private const val ACTIVATION_MS = 950
private const val ACTIVATION_GAP_MS = 650L

/**
 * An activation: the card's spot pulses with a ring in the player's colour, and the card pops up
 * large in the middle of the board, then fades.
 */
@Composable
private fun ActivationEffect(fx: ActivationFx, origin: Offset, color: Color, vm: AppViewModel, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    var started by remember { mutableStateOf(false) }
    val context = coil3.compose.LocalPlatformContext.current
    val big = vm.db.find(fx.code)?.imageUrl() ?: "https://images.ygoprodeck.com/images/cards/${fx.code}.jpg"
    val small = vm.db.find(fx.code)?.imageUrl(small = true) ?: "https://images.ygoprodeck.com/images/cards_small/${fx.code}.jpg"
    LaunchedEffect(fx.id) {
        // Start downloading the large picture now, so it's ready when the card pops up.
        val loader = coil3.SingletonImageLoader.get(context)
        val preload = async {
            kotlinx.coroutines.withTimeoutOrNull(4000) {
                loader.execute(coil3.request.ImageRequest.Builder(context).data(big).build())
            }
        }
        delay(fx.delayMs)
        // Give a slow download a little longer; the small picture covers it otherwise.
        kotlinx.coroutines.withTimeoutOrNull(PRELOAD_SLACK_MS) { preload.await() }
        started = true
        progress.animateTo(1f, tween(ACTIVATION_MS, easing = androidx.compose.animation.core.LinearEasing))
        onDone()
    }
    if (!started) return
    val t = progress.value
    val density = androidx.compose.ui.platform.LocalDensity.current

    // Pulse on the card's spot: a ring that grows and fades, over a soft glow.
    fx.spot?.let { r ->
        val w = with(density) { r.width.toDp() }
        val h = with(density) { r.height.toDp() }
        Box(
            Modifier
                .offset { IntOffset((r.left - origin.x).roundToInt(), (r.top - origin.y).roundToInt()) }
                .size(w, h)
                .graphicsLayer {
                    val grow = 1f + 0.5f * t
                    scaleX = grow
                    scaleY = grow
                    alpha = (1f - t).coerceIn(0f, 1f)
                }
                .border(3.dp, color, RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.25f), RoundedCornerShape(6.dp)),
        )
    }

    // The card itself, shown large in the middle: pops in, holds, fades out.
    val appear = (t / 0.2f).coerceIn(0f, 1f)
    val fade = ((1f - t) / 0.25f).coerceIn(0f, 1f)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(120.dp, (120 / 0.686f).dp)
                .graphicsLayer {
                    val s = 0.6f + 0.4f * FastOutSlowInEasing.transform(appear)
                    scaleX = s
                    scaleY = s
                    alpha = minOf(appear, fade)
                    shadowElevation = 24f
                }
                .border(3.dp, color, RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp)),
        ) {
            // The small picture is usually cached already (it's on the board); the large one draws over it.
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF5B3A1E), Color(0xFF2E1B0E)))))
            AsyncImage(model = small, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            AsyncImage(
                model = big,
                contentDescription = vm.db.find(fx.code)?.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // A light sweep across the card as it appears.
            val sweep = ((t - 0.1f) / 0.4f).coerceIn(0f, 1f)
            if (sweep in 0.01f..0.99f) Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        0f to Color.Transparent,
                        (sweep - 0.12f).coerceAtLeast(0f) to Color.Transparent,
                        sweep to Color.White.copy(alpha = 0.55f),
                        (sweep + 0.12f).coerceAtMost(1f) to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                ),
            )
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
private fun FlyingCard(f: Flight, origin: Offset, width: Dp, vm: AppViewModel, onDone: () -> Unit) {
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
