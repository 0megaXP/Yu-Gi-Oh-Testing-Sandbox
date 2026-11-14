package com.ygosandbox.desktop

import com.ygosandbox.app.platform.CrashReports
import java.awt.Component
import javax.swing.JOptionPane
import kotlin.system.exitProcess

/**
 * Errors on the desktop go to [CrashReports] (the app has no console to print to). An error that
 * breaks the window shows a message saying where the report is, then closes the app instead of
 * the window just vanishing; the next start shows the full explanation.
 */
object CrashLog {
    fun install() {
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { CrashReports.record(e, t.name, fatal = false) }
            e.printStackTrace()
        }
    }

    /** An error inside the window: report it and close. */
    fun fatal(e: Throwable, parent: Component?) {
        val file = runCatching { CrashReports.record(e, Thread.currentThread().name, fatal = true) }.getOrNull()
        e.printStackTrace()
        runCatching {
            JOptionPane.showMessageDialog(
                parent,
                "YGO Sandbox ran into an error and has to close.\n\n" +
                    "${e::class.simpleName}: ${e.message.orEmpty().take(300)}\n\n" +
                    "A detailed report was saved" + (file?.let { " to:\n${it.absolutePath}" } ?: ".") +
                    "\nIt will be explained when you open the app again.",
                "YGO Sandbox",
                JOptionPane.ERROR_MESSAGE,
            )
        }
        exitProcess(1)
    }
}
