@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ygosandbox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ygoengine.EngineCard
import com.ygoengine.ReferenceResult
import com.ygoengine.ReferenceSample
import com.ygosandbox.app.AppViewModel
import com.ygosandbox.app.UiState
import com.ygosandbox.app.model.GameFormat

private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.1f", v)

/** "Better than 80% of this deck's reference boards" for a board value. */
fun referenceLine(ref: ReferenceResult, value: Double): String {
    val p = (ref.percentile(value) * 100).toInt()
    return "Board value ${fmt(value)}: as good as or better than $p% of this deck's ${ref.samples.size} reference boards (median ${fmt(ref.median)}, best ${fmt(ref.best)})"
}

@Composable
fun ReferenceScreen(ui: UiState, vm: AppViewModel, deckKey: String) {
    val choice = remember(ui.myDecks, ui.decks, deckKey) { vm.deckChoice(deckKey) }
    if (choice == null) {
        Column { Header("Reference boards", onBack = { vm.back() }) }
        return
    }
    val play = ui.play
    val ref = remember(choice, play.refRevision) { vm.referenceFor(choice) }
    var pager by remember { mutableStateOf<Pair<List<Long>, Int>?>(null) }
    val open: (List<Long>, Int) -> Unit = { codes, i -> pager = codes to i }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header("Reference boards: ${choice.name}", onBack = { vm.back() }) { TestBadge(); Spacer(Modifier.width(12.dp)) }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "intro") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "The engine plays many random opening hands going first, with no interaction from the " +
                                "opponent, and keeps the best board it finds for each. That shows what this deck " +
                                "usually builds, and gives a yardstick for any other line: Play engine results " +
                                "show where their board sits against these.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        play.refError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        val p = play.refProgress
                        if (p != null) {
                            EngineProgress(p, play.preparing, onStop = { vm.stopReference() })
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.buildReference(deckKey, thorough = false) }) {
                                    Text(if (ref == null) "Build · quick" else "Rebuild · quick")
                                }
                                OutlinedButton(onClick = { vm.buildReference(deckKey, thorough = true) }) {
                                    Text("Thorough")
                                }
                            }
                            Text(
                                "Quick: 12 hands, about 1 minute. Thorough: 30 hands searched deeper, several minutes.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (ref != null && play.refProgress == null) {
                    item(key = "summary") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SectionTitle("Summary")
                            MissingCardsNote(vm, ref.missingCards)
                            val avgInterrupts = ref.samples.map { it.interrupts }.average()
                            Text(
                                "${ref.samples.size} hands · board value median ${fmt(ref.median)}, best ${fmt(ref.best)} · " +
                                    "${fmt(avgInterrupts)} interruptions on average" + if (ref.cancelled) " · stopped early" else "",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (ref.commonCards.isNotEmpty()) {
                                Text("Typical end board", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                val codes = ref.commonCards.map { it.code }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    itemsIndexed(ref.commonCards) { i, c ->
                                        Column(Modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            CardThumbnail(vm, c.code, width = 52) { open(codes, i) }
                                            Text(
                                                "${(c.share * 100).toInt()}%", style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                Text(
                                    "Share of reference boards with each card on the field at the end of the turn.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            SectionTitle("Boards, best first")
                        }
                    }
                    itemsIndexed(ref.samples, key = { i, _ -> "s$i" }) { i, s ->
                        SampleCard(vm, i + 1, s, open)
                    }
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
}

@Composable
private fun SampleCard(vm: AppViewModel, rank: Int, s: ReferenceSample, open: (List<Long>, Int) -> Unit) {
    var steps by rememberSaveable("ref$rank") { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "#$rank · value ${fmt(s.value)} · ${s.interrupts} interruption${if (s.interrupts == 1) "" else "s"}",
                fontWeight = FontWeight.SemiBold,
            )
            Text("Hand", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                s.hand.forEachIndexed { i, code -> CardThumbnail(vm, code, width = 40) { open(s.hand, i) } }
            }
            val field = s.board.filter { it.where != "hand" }
            Text(if (field.isEmpty()) "No board" else "End board", style = MaterialTheme.typography.labelMedium)
            if (field.isNotEmpty()) BoardCards(vm, field, open)
            val kept = s.board.filter { it.where == "hand" }
            if (kept.isNotEmpty()) Text(
                "Kept in hand: " + kept.joinToString(", ") { it.name },
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            OutlinedButton(onClick = { steps = !steps }) { Text(if (steps) "Hide line" else "Show line (${s.moves.size} steps)") }
            if (steps) s.moves.forEachIndexed { i, m ->
                Text("${i + 1}. ${m.replace("  →  ", " → ")}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun BoardCards(vm: AppViewModel, cards: List<EngineCard>, open: (List<Long>, Int) -> Unit) {
    val codes = cards.map { it.code }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cards.forEachIndexed { i, c ->
            Column(Modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CardThumbnail(vm, c.code, width = 48) { open(codes, i) }
                Text(
                    c.where.replace("Monster Zone", "Monster").replace("S/T Zone", "S/T").replace("Field Zone", "Field") +
                        if (c.note.isNotBlank()) " · ${c.note}" else "",
                    style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
