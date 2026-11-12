package com.ygosandbox.app.work

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.ygosandbox.app.MainActivity

/**
 * Keeps long jobs (reading videos, engine analyses) running while the app is in the background.
 *
 * While at least one job holds it, a foreground service shows a notification with the job's
 * progress and a Stop button, and keeps the CPU awake. Android then neither pauses nor kills
 * the app while you use another one or turn the screen off.
 */
object BackgroundWork {
    data class Status(val title: String, val text: String, val progress: Float?, val stop: () -> Unit)

    /**
     * For jobs that must outlive the screen they started from. Off the main thread: a job that
     * keeps the main thread busy for seconds gets the app killed in the background, silently.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal val jobs = MutableStateFlow<Map<String, Status>>(emptyMap())

    /** Starts holding the app awake for job [key]; [stop] is what the notification's Stop button does. */
    fun start(context: Context, key: String, title: String, stop: () -> Unit) {
        val first = jobs.value.isEmpty()
        jobs.update { it + (key to Status(title, "Starting…", null, stop)) }
        appContext = context.applicationContext
        mark(title, "Starting")
        if (first) runCatching {
            ContextCompat.startForegroundService(context.applicationContext, Intent(context.applicationContext, WorkService::class.java))
        }
        askBatteryOnce(context.applicationContext)
    }

    fun update(key: String, text: String, progress: Float?) {
        jobs.update { m -> m[key]?.let { m + (key to it.copy(text = text, progress = progress)) } ?: m }
        jobs.value[key]?.let { s ->
            val now = System.currentTimeMillis()
            if (now - lastMark > 5_000) mark(s.title, text)
        }
    }

    /** The job is over; the service stops when no job is left. */
    fun finish(key: String) {
        jobs.update { it - key }
        if (jobs.value.isEmpty()) appContext?.let { prefs(it).edit().remove("running").apply() }
    }

    // ---- what happened to a job that never finished (the app was killed, or crashed)

    @Volatile private var appContext: Context? = null
    @Volatile private var lastMark = 0L

    private fun prefs(c: Context) = c.getSharedPreferences("background", Context.MODE_PRIVATE)

    /** Notes the running job, so a run cut short by Android can be reported when the app opens again. */
    private fun mark(title: String, text: String) {
        val c = appContext ?: return
        lastMark = System.currentTimeMillis()
        prefs(c).edit().putString("running", "$title: ${text.replace('\n', ' ')}").apply()
    }

    /**
     * Call once when the app starts: records crashes from now on, and turns a job left marked as
     * running by a previous start (the app was closed mid-job) into a note for the user.
     */
    fun install(context: Context) {
        val c = context.applicationContext
        appContext = c
        val p = prefs(c)
        if (jobs.value.isEmpty()) p.getString("running", null)?.let { was ->
            val crash = p.getString("crash", null)
            val note = if (crash != null) "The app crashed during \"$was\".\nError: $crash"
            else "Android closed the app during \"$was\". Allow YGO Sandbox to run without battery restrictions " +
                "(Android settings → Apps → YGO Sandbox → Battery) and read it again: it continues where it stopped."
            p.edit().putString("interrupted", note).remove("running").remove("crash").apply()
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous !is CrashRecorder) Thread.setDefaultUncaughtExceptionHandler(CrashRecorder(c, previous))
    }

    /** The note about the last interrupted job, if there is one. */
    fun interruption(context: Context): String? = prefs(context).getString("interrupted", null)

    fun dismissInterruption(context: Context) = prefs(context).edit().remove("interrupted").apply()

    private class CrashRecorder(val c: Context, val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                val where = e.stackTrace.take(4).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                val p = c.getSharedPreferences("background", Context.MODE_PRIVATE)
                // commit, not apply: the process is about to end
                p.edit().putString("crash", "${e.javaClass.simpleName}: ${e.message ?: ""} ($where)")
                    .putString("running", p.getString("running", null) ?: "the app").commit()
            }
            next?.uncaughtException(t, e)
        }
    }

    /** Runs [block] in [scope], held awake under [key] until it ends. */
    fun launch(context: Context, key: String, title: String, block: suspend () -> Unit): Job {
        lateinit var job: Job
        job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                finish(key)
            }
        }
        start(context, key, title) { job.cancel() }
        job.start()
        return job
    }

    internal fun stopAll() = jobs.value.values.forEach { runCatching { it.stop() } }

    /**
     * Many phones' battery savers stop background work despite the notification. The first time a
     * long job starts, Android's own dialog asks to let the app run unrestricted (asked once).
     */
    private fun askBatteryOnce(context: Context) {
        val prefs = context.getSharedPreferences("background", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_battery", false)) return
        prefs.edit().putBoolean("asked_battery", true).apply()
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Android 13+ hides the notification unless this is granted; the work runs either way. */
    fun needsNotificationPermission(context: Context) =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
}

class WorkService : Service() {
    private var wake: PowerManager.WakeLock? = null
    // keeps Wi-Fi from dozing with the screen off, which stalls downloads
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Background work", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Reading videos and engine analyses while the app is in the background"
            }
        )
        startInForeground(build(BackgroundWork.jobs.value.values.firstOrNull()))
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ygosandbox:background-work")
            .apply { setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L) }
        wifi = runCatching {
            val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wm.createWifiLock(mode, "ygosandbox:background-work").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        watcher = BackgroundWork.scope.launch {
            BackgroundWork.jobs.collect { m ->
                if (m.isEmpty()) {
                    stopSelf()
                } else {
                    nm.notify(ID, build(m.values.first(), more = m.size - 1))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) BackgroundWork.stopAll()
        // nothing is held any more (the app was restarted): don't linger
        if (BackgroundWork.jobs.value.isEmpty()) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        super.onDestroy()
    }

    private fun startInForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ID, n, type)
    }

    private fun build(s: BackgroundWork.Status?, more: Int = 0): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WorkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle((s?.title ?: "YGO Sandbox") + if (more > 0) " (+$more more)" else "")
            .setContentText(s?.text ?: "Working…")
            .setStyle(NotificationCompat.BigTextStyle().bigText(s?.text ?: "Working…"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
        val p = s?.progress
        if (p == null) b.setProgress(0, 0, true) else b.setProgress(1000, (p * 1000).toInt().coerceIn(0, 1000), false)
        return b.build()
    }

    companion object {
        private const val CHANNEL = "background-work"
        private const val ID = 4201
        private const val ACTION_STOP = "com.ygosandbox.app.STOP_WORK"
    }
}
