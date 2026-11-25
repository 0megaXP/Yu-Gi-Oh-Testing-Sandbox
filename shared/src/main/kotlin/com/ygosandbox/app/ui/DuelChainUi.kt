@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ygosandbox.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ygoengine.DuelChainLink
import com.ygoengine.DuelPick
import com.ygoengine.DuelPlace
import com.ygoengine.DuelState
import com.ygosandbox.app.AppViewModel
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------- Turn and phase ----------------

private val PHASE_STEPS = listOf(
    "Draw Phase" to "DP", "Standby Phase" to "SP", "Main Phase 1" to "M1",
    "Battle Phase" to "BP", "Main Phase 2" to "M2", "End Phase" to "EP",
)

/**
 * Whose turn it is and the phase: the turn number in the turn player's colour, their name, and
 * the six phases with the current one lit (the ones already past dimmed).
 */
@Composable
internal fun PhaseBar(s: DuelState, names: List<String>, colors: PlayerColors, human: Int, modifier: Modifier = Modifier) {
    val color = colors.of(s.turnPlayer)
    val current = PHASE_STEPS.indexOfFirst { it.first == s.phase }
    val who = when {
        s.ended -> "Duel over"
        s.turnPlayer == human -> "Your turn"
        else -> "${names.getOrElse(s.turnPlayer) { "Player" }.substringBefore(" (")}'s turn"
    }
    Surface(
        color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth().border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(10.dp)),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Text("${s.turn}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Text(
                who, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            PHASE_STEPS.forEachIndexed { i, (_, short) ->
                val on = i == current
                val past = current >= 0 && i < current
                val bg by animateColorAsState(if (on) color else Color.Transparent, tween(250))
                Box(
                    Modifier.width(30.dp).height(22.dp).clip(RoundedCornerShape(6.dp)).background(bg)
                        .border(1.dp, if (on) color else color.copy(alpha = if (past) 0.25f else 0.45f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        short, fontSize = 10.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        color = when {
                            on -> Color.White
                            past -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

// ---------------- Chain ----------------

/** A picked card the viewer may not see: face-down on the field and not theirs to look at. */
private fun hiddenPick(p: DuelPick, canSee: (Int) -> Boolean): Boolean {
    val field = DuelPlace.MONSTER_ZONE or DuelPlace.SPELL_ZONE
    return p.code == 0L || (p.place.location and field != 0 && p.place.faceDown && !canSee(p.place.player)) ||
        (p.place.location and (DuelPlace.HAND or DuelPlace.DECK) != 0 && !canSee(p.place.player))
}

private fun whereText(p: DuelPlace, me: Int): String {
    val side = if (p.player == me) "your" else "opp."
    val l = p.location
    val zone = when {
        l and DuelPlace.MATERIAL != 0 -> "material"
        l and DuelPlace.MONSTER_ZONE != 0 -> "Monster Zone"
        l and DuelPlace.SPELL_ZONE != 0 -> if (p.sequence == 5) "Field Zone" else "S/T Zone"
        l and DuelPlace.GRAVEYARD != 0 -> "GY"
        l and DuelPlace.BANISHED != 0 -> "banished"
        l and DuelPlace.HAND != 0 -> "hand"
        l and DuelPlace.DECK != 0 -> "Deck"
        l and DuelPlace.EXTRA != 0 -> "Extra Deck"
        else -> ""
    }
    return "$side $zone".trim()
}

/**
 * The chain on the board, the last link (which resolves first) on top: each link's card, whose it
 * is, the effect, and the cards it targets. The link resolving is lit; negated ones are struck out.
 */
@Composable
internal fun ChainPanel(
    s: DuelState,
    vm: AppViewModel,
    colors: PlayerColors,
    bottom: Int,
    canSee: (Int) -> Boolean,
    onOpen: (List<Long>, Int) -> Unit,
) {
    if (s.chain.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Chain · ${s.chain.size} link${if (s.chain.size == 1) "" else "s"}" +
                if (s.chain.any { it.state == "resolving" }) " · resolving" else "",
            fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge,
        )
        s.chain.withIndex().reversed().forEach { (i, link) -> ChainLinkRow(i + 1, link, vm, colors, bottom, canSee, onOpen) }
    }
}

@Composable
private fun ChainLinkRow(
    n: Int,
    link: DuelChainLink,
    vm: AppViewModel,
    colors: PlayerColors,
    bottom: Int,
    canSee: (Int) -> Boolean,
    onOpen: (List<Long>, Int) -> Unit,
) {
    val color = colors.of(link.player)
    val resolving = link.state == "resolving"
    val done = link.state == "resolved"
    Surface(
        color = if (resolving) color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().then(if (resolving) Modifier.border(2.dp, color, RoundedCornerShape(10.dp)) else Modifier)
            .graphicsLayer { alpha = if (done) 0.55f else 1f },
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChainBadge(n, color, 22)
                Spacer(Modifier.width(8.dp))
                CardThumbnail(vm, link.code, width = 26) { onOpen(listOf(link.code), 0) }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        link.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textDecoration = if (link.negated) TextDecoration.LineThrough else null,
                    )
                    val note = listOfNotNull(
                        link.effect.takeIf { it.isNotBlank() },
                        if (link.negated) "negated" else null,
                        if (resolving) "resolving" else if (done) "resolved" else null,
                    ).joinToString(" · ")
                    if (note.isNotEmpty()) Text(
                        note, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (link.negated) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (link.picks.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(start = 30.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    link.picks.forEach { p -> PickChip(p, vm, color, bottom, canSee, onOpen) }
                }
            }
        }
    }
}

@Composable
private fun PickChip(p: DuelPick, vm: AppViewModel, color: Color, bottom: Int, canSee: (Int) -> Boolean, onOpen: (List<Long>, Int) -> Unit) {
    val hidden = hiddenPick(p, canSee)
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (p.target) "◎" else "✓", color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.width(4.dp))
        if (hidden) {
            Box(
                Modifier.width(18.dp).aspectRatio(0.686f).clip(RoundedCornerShape(2.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF5B3A1E), Color(0xFF2E1B0E)))),
            )
        } else {
            CardThumbnail(vm, p.code, width = 18) { onOpen(listOf(p.code), 0) }
        }
        Spacer(Modifier.width(5.dp))
        Column {
            Text(
                (if (p.target) "targets " else "picks ") + if (hidden) "a Set card" else p.name,
                style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(whereText(p.place, bottom), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChainBadge(n: Int, color: Color, size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(color).border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size * 0.55f).sp)
    }
}

/**
 * The chain drawn on the board: a numbered badge on each link's card, a ring on every card a
 * link targets with the link's number, and an arrow from the card to its targets.
 */
@Composable
internal fun ChainOverlay(s: DuelState, spots: BoardSpots, colors: PlayerColors) {
    if (s.chain.isEmpty()) return
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val pulse by rememberInfiniteTransition().animateFloat(0.55f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse))
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        // arrows first, under the rings and badges
        Canvas(Modifier.fillMaxSize()) {
            s.chain.forEach { link ->
                if (link.state == "resolved") return@forEach
                val from = spots.find(link.place)?.center?.minus(origin) ?: return@forEach
                val c = colors.of(link.player)
                link.picks.forEach { p ->
                    val to = spots.find(p.place)?.center?.minus(origin) ?: return@forEach
                    if ((to - from).getDistance() < 8f) return@forEach
                    // stop short of the target's centre so the arrowhead sits on its ring
                    val angle = atan2(to.y - from.y, to.x - from.x)
                    val stop = 18.dp.toPx()
                    val end = Offset(to.x - cos(angle) * stop, to.y - sin(angle) * stop)
                    drawLine(c.copy(alpha = 0.85f), from, end, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx())))
                    val head = 10.dp.toPx()
                    for (side in listOf(-0.5f, 0.5f)) {
                        val a = angle + Math.PI.toFloat() + side
                        drawLine(c, end, Offset(end.x + cos(a) * head, end.y + sin(a) * head), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
            }
        }
        s.chain.withIndex().forEach { (i, link) ->
            val c = colors.of(link.player)
            if (link.state != "resolved") link.picks.forEach { p ->
                spots.find(p.place)?.let { r -> TargetRing(r, origin, c, i + 1, pulse) }
            }
            spots.find(link.place)?.let { r ->
                val size = 20.dp
                val px = with(density) { size.toPx() }
                Box(
                    Modifier.offset { IntOffset((r.left - origin.x - px * 0.3f).roundToInt(), (r.top - origin.y - px * 0.3f).roundToInt()) }
                        .graphicsLayer { alpha = if (link.state == "resolved") 0.5f else 1f },
                ) {
                    Box(if (link.state == "resolving") Modifier.graphicsLayer { scaleX = 0.9f + 0.25f * pulse; scaleY = 0.9f + 0.25f * pulse } else Modifier) {
                        ChainBadge(i + 1, c, size.value.toInt())
                    }
                }
            }
        }
    }
}

/** A ring around a targeted card's spot, with the number of the link targeting it. */
@Composable
private fun TargetRing(r: Rect, origin: Offset, color: Color, link: Int, strength: Float) {
    val density = LocalDensity.current
    val w = with(density) { r.width.toDp() }
    val h = with(density) { r.height.toDp() }
    Box(
        Modifier.offset { IntOffset((r.left - origin.x).roundToInt(), (r.top - origin.y).roundToInt()) }.size(w, h),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            drawRoundRect(color.copy(alpha = 0.25f * strength), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
            drawRoundRect(color.copy(alpha = strength), style = Stroke(stroke), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
            // crosshair ticks on each side
            val t = 7.dp.toPx()
            val cx = size.width / 2
            val cy = size.height / 2
            drawLine(color, Offset(cx, 0f), Offset(cx, t), stroke)
            drawLine(color, Offset(cx, size.height), Offset(cx, size.height - t), stroke)
            drawLine(color, Offset(0f, cy), Offset(t, cy), stroke)
            drawLine(color, Offset(size.width, cy), Offset(size.width - t, cy), stroke)
        }
        if (link > 0) Box(
            Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp).clip(RoundedCornerShape(5.dp)).background(color)
                .padding(horizontal = 4.dp, vertical = 1.dp),
        ) {
            Text("◎$link", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** A target flashing on its spot (for updates whose chain is already over when they're shown). */
internal class PickFx(val id: Long, val spot: Rect?, val player: Int, val delayMs: Long, val target: Boolean)

@Composable
internal fun PickEffect(fx: PickFx, origin: Offset, color: Color, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(fx.id) {
        delay(fx.delayMs)
        started = true
        progress.animateTo(1f, tween(PICK_MS))
        onDone()
    }
    val r = fx.spot
    if (!started || r == null) return
    val t = progress.value
    // two quick pulses, then fade
    val strength = (0.55f + 0.45f * kotlin.math.abs(sin(t * Math.PI * 2)).toFloat()) * (1f - (t - 0.7f).coerceAtLeast(0f) / 0.3f)
    TargetRing(r, origin, color, 0, strength.coerceIn(0f, 1f))
}

internal const val PICK_MS = 1100
