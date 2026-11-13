package com.ygosandbox.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.ygosandbox.app.work.AndroidWork
import com.ygosandbox.app.ui.SandboxApp
import com.ygosandbox.app.ui.SandboxTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    // Android 13+: lets the background-work notification (progress and Stop) show.
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onResume() {
        super.onResume()
        vm.onScreen(true)
    }

    override fun onPause() {
        vm.onScreen(false)
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AndroidWork.install(this)
        com.ygosandbox.app.platform.AndroidFilePicker.install(this)
        if (savedInstanceState == null && AndroidWork.needsNotificationPermission(this))
            askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            SandboxTheme {
                SandboxApp(vm)
            }
        }
    }
}
