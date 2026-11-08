package com.ygosandbox.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ygosandbox.app.model.Card
import com.ygosandbox.app.model.CardStat
import com.ygosandbox.app.model.GameFormat

/** One page of the card viewer. [card] is null when the card isn't in the database. */
data class PagerItem(val cardId: Long, val name: String, val card: Card?, val stat: CardStat? = null)

/** Full-screen card details, with arrow buttons for the previous and next card. */
@Composable
fun CardPager(
    items: List<PagerItem>,
    start: Int,
    format: GameFormat,
    onClose: () -> Unit,
    actions: (@Composable (PagerItem) -> Unit)? = null,
) {
    BackHandler(onBack = onClose)
    // Opening a card from a search box: put the keyboard away.
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        focus.clearFocus()
        keyboard?.hide()
    }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
                Text(
                    "${pager.currentPage + 1} / ${items.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            val scope = rememberCoroutineScope()
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f),
                beyondViewportPageCount = 1,
                userScrollEnabled = false,
            ) { page ->
                CardPage(
                    items[page], format,
                    onPrev = if (page > 0) ({ scope.launch { pager.animateScrollToPage(page - 1) } }) else null,
                    onNext = if (page < items.size - 1) ({ scope.launch { pager.animateScrollToPage(page + 1) } }) else null,
                )
            }
            if (actions != null && items.isNotEmpty()) actions(items[pager.currentPage.coerceIn(0, items.size - 1)])
        }
    }
}

@Composable
private fun CardPage(item: PagerItem, format: GameFormat, onPrev: (() -> Unit)?, onNext: (() -> Unit)?) {
    val c = item.card
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ArrowButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous card", onPrev)
            AsyncImage(
                model = c?.imageUrl() ?: "https://images.ygoprodeck.com/images/cards/${item.cardId}.jpg",
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.weight(1f).aspectRatio(0.686f).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            ArrowButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next card", onNext)
        }
        Text(item.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        item.stat?.let { s -> StatBlock(s) }

        if (c == null) {
            Text(
                "This card isn't in the downloaded database yet. Update the card database in Settings.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(6.dp).height(18.dp).background(frameColor(c.frameType), RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(8.dp))
            Text(typeLine(c), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        if (c.isMonster) {
            val parts = buildList {
                if (c.attribute.isNotEmpty()) add(c.attribute)
                c.level?.let { add((if (c.isLink) "Link " else if (c.isXyz) "Rank " else "Level ") + it) }
                c.scale?.let { add("Scale $it") }
                add("ATK ${c.atk?.toString() ?: "?"}")
                if (!c.isLink) add("DEF ${c.def?.toString() ?: "?"}")
            }
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        }
        val ban = if (format == GameFormat.TCG) c.banTcg else c.banOcg
        val labels = buildList {
            if (c.archetype.isNotEmpty()) add("Archetype: ${c.archetype}")
            add("${format.label}: ${ban.ifEmpty { "Unlimited" }}")
        }
        Text(labels.joinToString("   "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
            Text(c.desc, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ArrowButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: (() -> Unit)?) {
    FilledTonalIconButton(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        modifier = Modifier.padding(horizontal = 6.dp).size(48.dp),
    ) { Icon(icon, label, Modifier.size(32.dp)) }
}

@Composable
private fun StatBlock(s: CardStat) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${if (s.engine) "Engine" else "Non-engine"} · ${s.usage.label}",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                statLine(s),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

fun pct(f: Float) = "${(f * 100).toInt()}%"
fun copies(f: Float) = if (f == 0f) "" else "×" + String.format(java.util.Locale.US, "%.1f", f)

/** "In 82% of decks · Main 80% ×2.7 · Side 30% ×1.0 · Both 25%" */
fun statLine(s: CardStat): String = buildList {
    add("In ${pct(s.anyRate)} of decks")
    if (s.mainRate > 0) add("${if (s.extraDeck) "Extra" else "Main"} ${pct(s.mainRate)} ${copies(s.avgMain)}".trim())
    if (s.sideRate > 0) add("Side ${pct(s.sideRate)} ${copies(s.avgSide)}".trim())
    if (s.bothRate > 0) add("Both ${pct(s.bothRate)}")
}
    // Keep each value together, so a long line only wraps between values.
    .joinToString(" · ") { it.replace(' ', '\u00A0') }

fun typeLine(c: Card): String =
    if (c.isMonster) "${c.race} / ${c.type.replace(" Monster", "")}"
    else "${c.race} ${c.type.replace(" Card", "")}"
