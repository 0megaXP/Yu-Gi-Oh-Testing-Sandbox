package com.ygosandbox.app.platform

import androidx.compose.runtime.Composable

/** The system Back button/gesture. (On the desktop the same function listens for Escape.) */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) =
    androidx.activity.compose.BackHandler(enabled, onBack)
