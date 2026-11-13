package com.ygosandbox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import com.ygoengine.ComboBranch
import com.ygoengine.ComboTree
import com.ygosandbox.app.AppViewModel
import com.ygosandbox.app.UiState
import com.ygosandbox.app.model.GameFormat

private fun pct(v: Double) = "${(v * 100).toInt()}/100"

@Composable
fun ComboTreeScreen(ui: UiState, vm: AppViewModel) {
    val play = ui.play
    val tree = play.tree
    val myDeck = vm.deckChoice(play.treeSetup?.myDeck)
    val reference = vm.referenceFor(myDeck)
    var pager by remember { mutableStateOf<Pair<List<Long>, Int>?>(null) }
    val open: (List<Long>, Int) -> Unit = { codes, i -> pager = codes to i }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header("Combo tree", onBack = { vm.back() }) { TestBadge(); Spacer(Modifier.width(12.dp)) }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "head") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        play.treeSetup?.let { s ->
                            Text("Your hand", style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                s.myHand.forEachIndexed { i, c -> CardThumbnail(vm, c, width = 44) { open(s.myHand, i) } }
                            }
                        }
                        play.treeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Text(
                            if (reference?.libraryJson != null)
                                "The search tries plans built from this deck's combo pieces (its reference boards) first."
                            else
                                "Tip: build this deck's reference boards to give the search its combo pieces.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        play.treeProgress?.let { EngineProgress(it, play.preparing, onStop = { vm.stopPlay() }) }
                    }
                }
                if (tree != null && play.treeProgress == null) {
                    item(key = "main") { MainLine(vm, tree, reference, open) }
                    item(key = "branchesTitle") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SectionTitle("If they interrupt")
                            Text(
                                (if (tree.opponentKnown) "Hand traps from their list" else "The most played hand traps") +
                                    ", each used where it hurts this line most. Worst first.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MissingCardsNote(vm, tree.missingCards)
                            if (tree.cancelled) Text(
                                "Stopped early: some hand traps were not checked.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    itemsIndexed(tree.branches, key = { i, _ -> "b$i" }) { i, b -> BranchCard(vm, i, b, tree, reference, open) }
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
private fun MainLine(vm: AppViewModel, tree: ComboTree, reference: com.ygoengine.ReferenceResult?, open: (List<Long>, Int) -> Unit) {
    var showSteps by rememberSaveable { mutableStateOf(false) }
    Column {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Main line (no interruption)", fontWeight = FontWeight.SemiBold)
            Text("Score ${pct(tree.mainScore)}", style = MaterialTheme.typography.labelSmall)
            val field = tree.mainBoard.filter { it.where != "hand" }
            if (field.isNotEmpty()) BoardRow(vm, field, open)
            if (reference != null) Text(referenceLine(reference, tree.mainValue), style = MaterialTheme.typography.labelSmall)
            Toggle(if (showSteps) "Hide steps" else "Steps (${tree.mainMoves.size})", showSteps) { showSteps = !showSteps }
            if (showSteps) Steps(tree.mainMoves, otherPrefix = "Opp: ")
        }
    }
    if (tree.mainParts.size > 1) {
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("The line in parts")
            Text(
                "Each part is a sub-combo: what it needs, what it gives the next parts, and which of the tested " +
                    "hand traps can stop it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            tree.mainParts.forEachIndexed { i, p ->
                val hitBy = tree.branches.filter { !it.stopsNothing && it.hitsPart == i }.map { it.handTrap.name }
                ComboPieceCard(
                    vm, key = "part$i", title = "Part ${i + 1}", piece = p, open = open,
                    subtitle = if (hitBy.isEmpty()) null else "Where " + hitBy.joinToString(", ") + " hurt${if (hitBy.size == 1) "s" else ""} most",
                    highlight = hitBy.isNotEmpty(),
                )
            }
        }
    }
    }
}

@Composable
private fun BranchCard(
    vm: AppViewModel,
    index: Int,
    b: ComboBranch,
    tree: ComboTree,
    reference: com.ygoengine.ReferenceResult?,
    open: (List<Long>, Int) -> Unit,
) {
    var showSteps by rememberSaveable("branch$index") { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardThumbnail(vm, b.handTrap.code, width = 40) { open(listOf(b.handTrap.code), 0) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("If they use ${b.handTrap.name}", fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (b.stopsNothing) {
                        Text("It has nowhere to hit this line.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    } else {
                        val lost = ((tree.mainScore - b.score) * 100).toInt()
                        Text(
                            "Worst moment: in response to ${b.usedOn.substringBefore(" — ").substringBefore("  →  ")}" +
                                if (b.hitsPart >= 0 && tree.mainParts.size > 1) " (part ${b.hitsPart + 1})" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Score ${pct(b.score)}" + (if (lost > 0) " (−$lost)" else "") +
                                " · could be used at ${b.chokePoints} point${if (b.chokePoints == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (lost >= 15) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (!b.stopsNothing) {
                val field = b.board.filter { it.where != "hand" }
                Text(if (field.isEmpty()) "No board" else "Board you still reach", style = MaterialTheme.typography.labelMedium)
                if (field.isNotEmpty()) BoardRow(vm, field, open)
                if (reference != null && b.value > 0) Text(referenceLine(reference, b.value), style = MaterialTheme.typography.labelSmall)
                Toggle(if (showSteps) "Hide continuation" else "How to continue (${b.continuation.size} steps)", showSteps) {
                    showSteps = !showSteps
                }
                if (showSteps) Steps(b.continuation, otherPrefix = "Opp: ")
            }
        }
    }
}
