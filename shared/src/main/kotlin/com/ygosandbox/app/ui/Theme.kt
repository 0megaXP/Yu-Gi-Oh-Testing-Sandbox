package com.ygosandbox.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Gold = Color(0xFFD9B44A)

private val Dark = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF2A1F00),
    primaryContainer = Color(0xFF3A3018),
    onPrimaryContainer = Color(0xFFFFE08F),
    secondary = Color(0xFFB7A6E8),
    secondaryContainer = Color(0xFF342A57),
    onSecondaryContainer = Color(0xFFE6DDFF),
    tertiary = Color(0xFF7FD1C2),
    background = Color(0xFF110F1A),
    surface = Color(0xFF110F1A),
    surfaceVariant = Color(0xFF26223A),
    surfaceContainer = Color(0xFF1A1727),
    surfaceContainerHigh = Color(0xFF221E33),
    surfaceContainerHighest = Color(0xFF2B263F),
    onSurfaceVariant = Color(0xFFBDB6D2),
    outlineVariant = Color(0xFF3B3552),
)

private val Light = lightColorScheme(
    primary = Color(0xFF735C00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE08F),
    onPrimaryContainer = Color(0xFF241A00),
    secondary = Color(0xFF5B4B91),
    secondaryContainer = Color(0xFFE6DDFF),
    onSecondaryContainer = Color(0xFF1A0B4A),
    tertiary = Color(0xFF00695C),
    background = Color(0xFFFAF8FD),
    surface = Color(0xFFFAF8FD),
    surfaceVariant = Color(0xFFE7E1F0),
    surfaceContainer = Color(0xFFF1EDF7),
    surfaceContainerHigh = Color(0xFFEAE5F2),
    surfaceContainerHighest = Color(0xFFE3DDEC),
)

@Composable
fun SandboxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}

/** The colour of a card's frame, used as a small stripe next to its name. */
fun frameColor(frameType: String): Color = when {
    frameType.startsWith("normal") -> Color(0xFFE3C46F)
    frameType.startsWith("effect") -> Color(0xFFD9822B)
    frameType.startsWith("ritual") -> Color(0xFF5C8FD6)
    frameType.startsWith("fusion") -> Color(0xFF9B59B6)
    frameType.startsWith("synchro") -> Color(0xFFE8E8E8)
    frameType.startsWith("xyz") -> Color(0xFF3A3A3A)
    frameType.startsWith("link") -> Color(0xFF2F6DB5)
    frameType == "spell" -> Color(0xFF1D9E74)
    frameType == "trap" -> Color(0xFFBC5A84)
    else -> Color(0xFF888888)
}
