package com.ygosandbox.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Crash reports. The app notes what it does as it goes (screens, duel moves, engine calls) in
 * crash/session.log. When it closes badly, the next start writes a detailed report to
 * crash/crash-report-<time>.txt (the app's version and system, what it was doing, the last
 * actions, and the error: the app's own, the engine's, or the system's crash file) and shows a
 * window explaining it.
 */
object CrashReports {
    /** What the window after a crash shows. */
    data class Notice(
        /** In plain words: what went wrong. */
        val summary: String,
        /** What the app was doing at the time. */
        val whileDoing: String,
        /** The whole report (also in [file]). */
        val report: String,
        val file: File,
    )

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice

    /** What the app is doing now (screen, duel turn), added to reports. */
    @Volatile var context: () -> String = { "" }

    private val dir get() = File(AppPlatform.current.filesDir, "crash")
    private val sessionLog get() = File(dir, "session.log")
    private val marker get() = File(dir, "running")
    /** Where the engine notes an error that ends the app (see YgoEngine.crashFile). */
    val engineFile: File get() = File(dir, "engine-crash.txt")

    private val recent = ArrayDeque<String>()
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    @Volatile private var started = false

    /** Notes an action, for the report if the app crashes (kept short: the last few hundred). */
    fun note(text: String) {
        val line = LocalDateTime.now().format(time) + "  " + text.replace('\n', ' ').take(400)
        synchronized(this) {
            recent.addLast(line)
            while (recent.size > 300) recent.removeFirst()
            if (started) runCatching { sessionLog.appendText(line + "\n") }
        }
    }

    /**
     * Call once at start, after [AppPlatform.current] is set. [uncleanExitMeansCrash]: whether a
     * session that didn't end with [cleanExit] crashed (true on a computer; on a phone the system
     * also ends apps that are in the background, so only recorded errors count there).
     */
    fun start(uncleanExitMeansCrash: Boolean) {
        runCatching {
            dir.mkdirs()
            val crashed = marker.exists()
            val since = marker.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
            val last = sessionLog.takeIf { it.exists() }?.readLines()?.takeLast(150).orEmpty()
            val pending = File(dir, "pending.txt").takeIf { it.exists() }
            when {
                // an error the app caught and wrote down before closing
                pending != null -> {
                    val file = File(pending.readText().trim())
                    pending.delete()
                    val report = runCatching { file.readText() }.getOrDefault("")
                    val summary = report.lineSequence().firstOrNull { it.startsWith("What happened: ") }?.removePrefix("What happened: ")
                        ?: "The app ran into an error."
                    _notice.value = Notice(summary, last.lastOrNull()?.drop(14) ?: "", report, file)
                }
                crashed && uncleanExitMeansCrash || evidence(since).isNotEmpty() ||
                    crashed && AppPlatform.current.lastExitReason(since) != null -> afterCrash(since, last)
            }
            sessionLog.writeText("")
            marker.writeText(System.currentTimeMillis().toString())
            started = true
        }
        note("Started YGO Sandbox ${AppPlatform.current.versionName} on ${system()}")
        runCatching { com.ygoengine.YgoEngine.crashFile(engineFile.absolutePath) }
    }

    /** The app is closing normally: no report next time. */
    fun cleanExit() {
        note("Closed")
        runCatching { marker.delete() }
    }

    /**
     * An error the app caught ([fatal]: it has to close now). Writes the report; a fatal one is
     * shown at the next start, otherwise it is only kept on disk.
     */
    fun record(e: Throwable, thread: String, fatal: Boolean): File {
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        val ours = e.stackTrace.firstOrNull { it.className.startsWith("com.ygo") }
        val summary = "An error in the app: ${e::class.simpleName}" + (e.message?.let { ": ${it.take(200)}" } ?: "") +
            (ours?.let { " (in ${it.className.substringAfterLast('.')}.${it.methodName}, line ${it.lineNumber})" } ?: "")
        val report = build(summary, "Thread: $thread\n\n$trace")
        val file = save(report)
        if (fatal) runCatching {
            File(dir, "pending.txt").writeText(file.absolutePath)
            marker.delete()
        }
        return file
    }

    fun dismiss() { _notice.value = null }

    // ---------------------------------------------------------------- after a crash

    /** Crash files written since [since] (ms): the engine's note and the system's crash reports. */
    private fun evidence(since: Long): List<File> =
        (listOf(engineFile) + AppPlatform.current.crashFiles())
            .filter { it.exists() && it.length() > 0 && it.lastModified() >= since - 1000 }

    private fun afterCrash(since: Long, last: List<String>) {
        val files = evidence(since)
        val engineLine = engineFile.takeIf { it in files }?.readLines()?.lastOrNull { it.isNotBlank() }
        val system = files.firstOrNull { it != engineFile }
        val systemText = system?.let { runCatching { it.readText() }.getOrNull() }
        val summary = when {
            engineLine != null -> {
                val parts = engineLine.split('\t')
                "The play engine (the part that runs the duel rules and the bots) hit an error it couldn't recover from" +
                    " during ${parts.getOrNull(1)?.removePrefix("engine call: ") ?: "a call"}: ${parts.getOrNull(2) ?: "no message"}."
            }
            systemText != null -> explainSystemCrash(systemText)
            else -> AppPlatform.current.lastExitReason(since)
                ?: "YGO Sandbox closed without recording an error. This happens when native code (the play engine) " +
                "stops the whole app at once, or when the app is ended from outside (Task Manager, shutdown, the battery)."
        }
        val details = buildString {
            if (engineLine != null) append("Engine note: $engineLine\n\n")
            if (system != null) {
                append("System crash file: ${system.absolutePath}\n")
                append(systemText.orEmpty().lineSequence().take(120).joinToString("\n"))
                append("\n")
            }
        }
        val doing = last.lastOrNull { !it.contains("Started YGO Sandbox") }?.drop(14) ?: ""
        val report = build(summary, details, last, doing)
        show(summary, doing, report)
        runCatching { engineFile.delete() }
    }

    /** Plain words for a JVM crash file (hs_err_pid*.log). */
    private fun explainSystemCrash(text: String): String {
        val frame = Regex("# Problematic frame:\\s*\\n#\\s*(.+)").find(text)?.groupValues?.get(1)?.trim()
        val kind = Regex("#\\s+(EXCEPTION_\\w+|SIG\\w+)").find(text)?.groupValues?.get(1)
        val where = when {
            frame == null -> "the app"
            "ygoe" in frame -> "the play engine (the part that runs the duel rules and the bots)"
            "skiko" in frame || "skia" in frame -> "the graphics library that draws the window"
            "jvm" in frame -> "Java itself"
            else -> "native code ($frame)"
        }
        val what = when {
            text.contains("Out of Memory", ignoreCase = true) || text.contains("OutOfMemory") -> "ran out of memory"
            kind == "EXCEPTION_STACK_OVERFLOW" -> "ran out of stack space (a calculation went too deep)"
            kind == "EXCEPTION_ACCESS_VIOLATION" || kind == "SIGSEGV" -> "read memory it shouldn't have (a bug in it)"
            kind != null -> "stopped with $kind"
            else -> "crashed"
        }
        return "The app closed because $where $what."
    }

    // ---------------------------------------------------------------- report

    private fun system(): String =
        "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")}), " +
            "Java ${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} cores, " +
            "memory limit ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB"

    private fun build(summary: String, details: String, last: List<String> = synchronized(this) { recent.toList() }, doing: String? = null): String =
        buildString {
            append("YGO Sandbox crash report\n")
            append("Written: ${LocalDateTime.now()}\n")
            append("App version: ${AppPlatform.current.versionName} (${AppPlatform.current.name})\n")
            append("System: ${system()}\n")
            append("What happened: $summary\n")
            val now = runCatching { context() }.getOrDefault("")
            if (doing != null) append("Last action: $doing\n") else if (now.isNotBlank()) append("App state: $now\n")
            append("\n---- What the app did last (oldest first) ----\n")
            last.forEach { append(it).append('\n') }
            if (details.isNotBlank()) append("\n---- Error details ----\n").append(details).append('\n')
        }

    private fun save(report: String): File {
        val f = File(dir, "crash-report-${LocalDateTime.now().format(stamp)}.txt")
        runCatching {
            dir.mkdirs()
            f.writeText(report)
            // keep the 20 newest reports
            dir.listFiles { x -> x.name.startsWith("crash-report-") }?.sortedByDescending { it.name }?.drop(20)?.forEach { it.delete() }
        }
        return f
    }

    private fun show(summary: String, doing: String, report: String) {
        val file = save(report)
        _notice.value = Notice(summary, doing, report, file)
    }

    /** "2026-11-14 07:35" for a time in ms. */
    fun formatTime(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}
