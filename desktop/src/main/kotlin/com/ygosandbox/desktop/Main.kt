package com.ygosandbox.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
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
import com.ygosandbox.app.platform.CrashReports
import com.ygosandbox.app.ui.SandboxTheme
import com.ygosandbox.app.work.BackgroundWork
import okio.Path.Companion.toOkioPath
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // The installer puts the engine DLL and the card pack in the app's resources folder.
    val resources = System.getProperty("compose.application.resources.dir")?.let(::File)
    resources?.let { File(it, System.mapLibraryName("ygoe_jni")) }?.takeIf { it.exists() }
        ?.let { System.setProperty("ygoe.library", it.absolutePath) }

    val platform = DesktopPlatform(resources)
    AppPlatform.current = platform
    CrashLog.install()
    // notes what the app does, and explains the last run's crash, if it crashed
    CrashReports.start(uncleanExitMeansCrash = System.getenv("YGO_TOUR") == null)
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
    CrashReports.context = { vm.crashContext() }
    val viewModels = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    // Screenshot tour (CI only): YGO_TOUR=<folder>, YGO_TOUR_SIZE=<w>x<h>.
    val tour = System.getenv("YGO_TOUR")?.let(::File)
    val size = System.getenv("YGO_TOUR_SIZE")?.split('x')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }
        ?.let { DpSize(it[0].dp, it[1].dp) } ?: DpSize(1100.dp, 880.dp)

    application {
        CompositionLocalProvider(
            LocalWindowExceptionHandlerFactory provides WindowExceptionHandlerFactory { w ->
                WindowExceptionHandler { e -> CrashLog.fatal(e, w) }
            },
        ) {
        Window(
            onCloseRequest = { CrashReports.cleanExit(); exitApplication() },
            title = "YGO Sandbox",
            state = rememberWindowState(
                size = size,
                position = if (tour != null) WindowPosition(0.dp, 0.dp) else WindowPosition.PlatformDefault,
            ),
            onKeyEvent = { e -> e.type == KeyEventType.KeyDown && e.key == Key.Escape && DesktopBack.dispatch() },
        ) {
            // Smaller than this and the board and lists stop fitting.
            LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(720, 600) }
            if (tour != null) LaunchedEffect(Unit) { Tour(vm, window, tour).run(); CrashReports.cleanExit(); exitApplication() }
            CompositionLocalProvider(LocalViewModelStoreOwner provides remember { viewModels }) {
                SandboxTheme {
                    SandboxApp(vm)
                }
            }
        }
        }
    }
}
