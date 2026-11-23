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
import coil3.compose.AsyncImage
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
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

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
                p.phase == "reference" -> "Building reference boards… hand ${(p.done + 1).coerceAtMost(p.total)} of ${p.total}"
                p.phase.startsWith("tree: ") -> "Combo tree: ${p.phase.removePrefix("tree: ")}… (${p.done + 1}/${p.total})"
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

/** "TEST" label; tap or long-press it for an explanation. */
@Composable
fun TestBadge() {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text("Test feature: still being worked on, so the bots may play oddly or a duel may stop.")
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
