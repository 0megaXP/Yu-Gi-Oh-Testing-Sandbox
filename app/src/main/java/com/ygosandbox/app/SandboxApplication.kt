package com.ygosandbox.app

import android.app.Application
import com.ygosandbox.app.platform.AndroidPlatform
import com.ygosandbox.app.platform.AppPlatform

/** Sets up the shared app's platform before any screen, view model or service runs. */
class SandboxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPlatform.current = AndroidPlatform(this)
    }
}
