package com.ygosandbox.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import com.ygosandbox.app.AppViewModel
import com.ygosandbox.app.platform.AppPlatform
import com.ygosandbox.app.platform.DesktopBack
import com.ygosandbox.app.ui.SandboxApp
import com.ygosandbox.app.ui.SandboxTheme
import com.ygosandbox.app.work.BackgroundWork
import okio.Path.Companion.toOkioPath
import java.io.File

fun main() {
    // The installer puts the engine DLL and the card pack in the app's resources folder.
    val resources = System.getProperty("compose.application.resources.dir")?.let(::File)
    resources?.let { File(it, "ygoe_jni.dll") }?.takeIf { it.exists() }
        ?.let { System.setProperty("ygoe.library", it.absolutePath) }

    val platform = DesktopPlatform(resources)
    AppPlatform.current = platform
    BackgroundWork.install()

    // Card pictures: kept on disk like on the phone, so they load instantly the second time.
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .diskCache {
                DiskCache.Builder()
                    .directory(File(platform.cacheDir, "images").toOkioPath())
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .build()
    }

    val vm = AppViewModel()
    val viewModels = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "YGO Sandbox",
            state = rememberWindowState(size = DpSize(1100.dp, 880.dp)),
            onKeyEvent = { e -> e.type == KeyEventType.KeyDown && e.key == Key.Escape && DesktopBack.dispatch() },
        ) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides remember { viewModels }) {
                SandboxTheme {
                    SandboxApp(vm)
                }
            }
        }
    }
}
