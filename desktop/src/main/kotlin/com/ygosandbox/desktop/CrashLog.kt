package com.ygosandbox.desktop

import com.ygosandbox.app.platform.AppPlatform
import java.awt.Component
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import javax.swing.JOptionPane
import kotlin.system.exitProcess

/**
 * Errors are written to %APPDATA%\YGO Sandbox\crash.log (the app has no console to print to).
 * An error that breaks the window shows a message saying where the details are, then closes the
 * app, instead of the window just vanishing.
 */
object CrashLog {
    /** What the app was doing, added to each report (the current screen). */
    var context: () -> String = { "" }

    val file: File get() = File(AppPlatform.current.filesDir, "crash.log")

    @Synchronized
    fun write(e: Throwable, thread: String = Thread.currentThread().name) {
        runCatching {
            val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
            val where = runCatching { context() }.getOrDefault("")
            // Keep the file small: the newest reports, at most about 200 KB.
            val old = file.takeIf { it.exists() }?.readText()?.takeLast(200_000).orEmpty()
            file.writeText(
                old + "\n===== ${LocalDateTime.now()} · YGO Sandbox ${AppPlatform.current.versionName} · " +
                    "${System.getProperty("os.name")} · thread $thread\n" +
                    (if (where.isNotEmpty()) "$where\n" else "") + trace
            )
        }
        e.printStackTrace()
    }

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler { t, e -> write(e, t.name) }
    }

    /** An error inside the window: report it and close. */
    fun fatal(e: Throwable, parent: Component?) {
        write(e)
        runCatching {
            JOptionPane.showMessageDialog(
                parent,
                "YGO Sandbox ran into an error and has to close.\n\n" +
                    "${e::class.simpleName}: ${e.message.orEmpty().take(300)}\n\n" +
                    "The details are in:\n${file.absolutePath}",
                "YGO Sandbox",
                JOptionPane.ERROR_MESSAGE,
            )
        }
        exitProcess(1)
    }
}
