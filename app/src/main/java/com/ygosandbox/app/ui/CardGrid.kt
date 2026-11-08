package com.ygosandbox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ygosandbox.app.model.Card

/** Number of card pictures per grid row. */
const val GRID_COLUMNS = 5

/**
 * Adds a grid of card pictures to a list: one picture per copy, so a card played 3 times shows 3 times.
 * [cards] are (id, card) pairs in display order, already repeated per copy.
 */
fun LazyListScope.cardGrid(keyPrefix: String, cards: List<Pair<Long, Card?>>, onClick: (Long) -> Unit) {
    cards.chunked(GRID_COLUMNS).forEachIndexed { row, chunk ->
        item(key = "${keyPrefix}_row$row") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                chunk.forEach { (id, card) ->
                    AsyncImage(
                        model = card?.imageUrl(small = true) ?: "https://images.ygoprodeck.com/images/cards_small/$id.jpg",
                        contentDescription = card?.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).aspectRatio(0.686f).clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onClick(id) },
                    )
                }
                // Keep the last row's pictures the same size as the others.
                repeat(GRID_COLUMNS - chunk.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** Switches deck lists between the picture grid and the list view. */
@Composable
fun GridToggle(grid: Boolean, onChange: (Boolean) -> Unit) {
    IconButton(onClick = { onChange(!grid) }) {
        if (grid) Icon(Icons.AutoMirrored.Filled.ViewList, "Show as list")
        else Icon(Icons.Default.GridView, "Show as grid")
    }
}
