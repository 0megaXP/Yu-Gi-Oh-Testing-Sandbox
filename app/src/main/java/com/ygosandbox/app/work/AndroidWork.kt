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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.ygosandbox.app.MainActivity

/**
 * Android side of [BackgroundWork]: while at least one job runs, a foreground service shows a
 * notification with the job's progress and a Stop button, and keeps the CPU awake. Android then
 * neither pauses nor kills the app while you use another one or turn the screen off.
 */
object AndroidWork {
    @Volatile private var appContext: Context? = null

    /** Call once when the app starts. */
    fun install(context: Context) {
        appContext = context.applicationContext
        BackgroundWork.install()
    }

    /** The first job started: start the service (and ask about battery limits once). */
    fun onWorkStarted(first: Boolean) {
        val c = appContext ?: return
        if (first) runCatching { ContextCompat.startForegroundService(c, Intent(c, WorkService::class.java)) }
        askBatteryOnce(c)
    }

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
