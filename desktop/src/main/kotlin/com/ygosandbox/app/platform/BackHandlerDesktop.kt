package com.ygosandbox.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * The desktop's Back: Escape closes the innermost open thing (a card, a dialog-like panel, then
 * the current screen), like the phone's Back button. Screens also have their own back arrows.
 */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val current by rememberUpdatedState(onBack)
    val active by rememberUpdatedState(enabled)
    DisposableEffect(Unit) {
        val handler = DesktopBack.Handler { if (active) { current(); true } else false }
        DesktopBack.add(handler)
        onDispose { DesktopBack.remove(handler) }
    }
}

/** The handlers on screen, innermost last. */
object DesktopBack {
    fun interface Handler {
        fun handle(): Boolean
    }

    private val handlers = ArrayList<Handler>()

    @Synchronized fun add(h: Handler) { handlers += h }
    @Synchronized fun remove(h: Handler) { handlers -= h }

    /** Runs the innermost enabled handler; false when none took it. */
    fun dispatch(): Boolean {
        val list = synchronized(this) { handlers.toList() }
        return list.asReversed().any { it.handle() }
    }
}
