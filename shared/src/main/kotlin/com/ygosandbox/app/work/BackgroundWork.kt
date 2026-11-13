package com.ygosandbox.app.work

import com.ygosandbox.app.platform.AppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Long jobs (reading videos, engine analyses) that keep running while the app is in the
 * background. The platform decides how: on Android a foreground service with a notification and
 * a Stop button holds the app awake; on the desktop the app simply keeps running.
 */
object BackgroundWork {
    data class Status(val title: String, val text: String, val progress: Float?, val stop: () -> Unit)

    /**
     * For jobs that must outlive the screen they started from. Off the main thread: a job that
     * keeps the main thread busy for seconds gets the app killed in the background, silently.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _jobs = MutableStateFlow<Map<String, Status>>(emptyMap())
    val jobs: StateFlow<Map<String, Status>> get() = _jobs

    @Volatile private var lastMark = 0L

    private fun prefs() = AppPlatform.current.preferences("background")

    /** Starts holding the app awake for job [key]; [stop] is what the Stop button does. */
    fun start(key: String, title: String, stop: () -> Unit) {
        val first = _jobs.value.isEmpty()
        _jobs.update { it + (key to Status(title, "Starting…", null, stop)) }
        mark(title, "Starting")
        runCatching { AppPlatform.current.onWorkStarted(first) }
    }

    fun update(key: String, text: String, progress: Float?) {
        _jobs.update { m -> m[key]?.let { m + (key to it.copy(text = text, progress = progress)) } ?: m }
        _jobs.value[key]?.let { s ->
            val now = System.currentTimeMillis()
            if (now - lastMark > 5_000) mark(s.title, text)
        }
    }

    /** The job is over; the platform lets go when no job is left. */
    fun finish(key: String) {
        _jobs.update { it - key }
        if (_jobs.value.isEmpty()) runCatching { prefs().edit().remove("running").apply() }
    }

    // ---- what happened to a job that never finished (the app was closed, or crashed)

    /** Notes the running job, so a run cut short can be reported when the app opens again. */
    private fun mark(title: String, text: String) {
        lastMark = System.currentTimeMillis()
        runCatching { prefs().edit().putString("running", "$title: ${text.replace('\n', ' ')}").apply() }
    }

    /**
     * Call once when the app starts (after [AppPlatform.current] is set): records crashes from
     * now on, and turns a job left marked as running by a previous start into a note for the user.
     */
    fun install() {
        val p = prefs()
        if (_jobs.value.isEmpty()) p.getString("running", null)?.let { was ->
            val crash = p.getString("crash", null)
            val note = if (crash != null) "The app crashed during \"$was\".\nError: $crash"
            else "${AppPlatform.current.name} closed the app during \"$was\". ${AppPlatform.current.interruptedHint}"
            p.edit().putString("interrupted", note).remove("running").remove("crash").apply()
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous !is CrashRecorder) Thread.setDefaultUncaughtExceptionHandler(CrashRecorder(previous))
    }

    /** The note about the last interrupted job, if there is one. */
    fun interruption(): String? = prefs().getString("interrupted", null)

    fun dismissInterruption() = prefs().edit().remove("interrupted").apply()

    private class CrashRecorder(val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                val where = e.stackTrace.take(4).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                val p = AppPlatform.current.preferences("background")
                // commit, not apply: the process is about to end
                p.edit().putString("crash", "${e.javaClass.simpleName}: ${e.message ?: ""} ($where)")
                    .putString("running", p.getString("running", null) ?: "the app").commit()
            }
            next?.uncaughtException(t, e)
        }
    }

    /** Runs [block] in [scope], held awake under [key] until it ends. */
    fun launch(key: String, title: String, block: suspend () -> Unit): Job {
        lateinit var job: Job
        job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                finish(key)
            }
        }
        start(key, title) { job.cancel() }
        job.start()
        return job
    }

    fun stopAll() = _jobs.value.values.forEach { runCatching { it.stop() } }
}
