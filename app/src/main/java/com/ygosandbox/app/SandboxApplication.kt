package com.ygosandbox.app

import android.app.Application
import com.ygosandbox.app.platform.AndroidPlatform
import com.ygosandbox.app.platform.AppPlatform
import com.ygosandbox.app.platform.CrashReports

/** Sets up the shared app's platform before any screen, view model or service runs. */
class SandboxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val platform = AndroidPlatform(this)
        AppPlatform.current = platform
        // a newer engine downloaded from the engine's releases (else the app's own, from the APK)
        com.ygosandbox.app.update.EngineUpdate.select(platform.engineLibAsset)?.let {
            System.setProperty("ygoe.library", it.lib.absolutePath)
        }
        // Android also ends apps in the background, so only recorded errors count as crashes.
        CrashReports.start(uncleanExitMeansCrash = false)
        val system = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { CrashReports.record(e, t.name, fatal = true) }
            system?.uncaughtException(t, e)
        }
    }
}
