package com.ygosandbox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ygoengine.ComboPiece
import com.ygoengine.PieceCard
import com.ygosandbox.app.AppViewModel

private fun whereText(w: String) = when (w) {
    "hand" -> "in hand"
    "field" -> "on the field"
    "gy" -> "in the GY"
    "banished" -> "banished"
    else -> w
}

@Composable
private fun CardRow(vm: AppViewModel, cards: List<PieceCard>, open: (List<Long>, Int) -> Unit) {
    val codes = cards.map { it.code }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cards.forEachIndexed { i, c -> CardThumbnail(vm, c.code, width = 36) { open(codes, i) } }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Note(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/**
 * One sub-combo: what it needs, what it gives, and what can stop it.
 * [key] keeps the steps toggle per card; [highlight] marks the part a hand trap hits.
 */
@Composable
fun ComboPieceCard(
    vm: AppViewModel,
    key: String,
    title: String,
    piece: ComboPiece,
    open: (List<Long>, Int) -> Unit,
    subtitle: String? = null,
    highlight: Boolean = false,
) {
    var showSteps by rememberSaveable("piece$key") { mutableStateOf(false) }
    Surface(
        color = if (highlight) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title + if (piece.starter) " · starter" else "", fontWeight = FontWeight.SemiBold)
            subtitle?.let { Note(it) }

            Label("Needs")
            if (piece.needsHand.isNotEmpty()) {
                Note("From your hand:")
                CardRow(vm, piece.needsHand, open)
            }
            if (piece.needsEarlier.isNotEmpty())
                Note("From earlier parts: " + piece.needsEarlier.joinToString(", ") { "${it.name} (${whereText(it.where)})" })
            if (piece.normalSummon) Note("Your Normal Summon")
            if (piece.needsHand.isEmpty() && piece.needsEarlier.isEmpty() && !piece.normalSummon) Note("Nothing new")

            if (piece.gives.isNotEmpty()) {
                Label("Gives the next parts")
                CardRow(vm, piece.gives, open)
            }
            if (piece.leaves.isNotEmpty()) {
                Label("Leaves at the end")
                CardRow(vm, piece.leaves, open)
            }

            if (piece.stoppedBy.isNotEmpty()) {
                Label("Can be stopped by")
                Note(
                    piece.stoppedBy.joinToString(", ") { "${it.name} (from step ${it.step + 1})" },
                    MaterialTheme.colorScheme.error,
                )
            } else {
                Note("No common hand trap can respond during it.", MaterialTheme.colorScheme.primary)
            }

            Toggle(if (showSteps) "Hide steps" else "Steps (${piece.steps.size})", showSteps) { showSteps = !showSteps }
            if (showSteps) Steps(piece.steps, otherPrefix = "Opp: ")
        }
    }
}
