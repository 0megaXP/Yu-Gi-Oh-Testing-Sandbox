package com.ygosandbox.app.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The system file picker, registered by the activity and awaited from shared code. */
object AndroidFilePicker {
    private var launcher: ActivityResultLauncher<Array<String>>? = null
    private var pending: CompletableDeferred<List<Uri>>? = null
    private var context: Context? = null

    fun install(activity: ComponentActivity) {
        context = activity.applicationContext
        launcher = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            pending?.complete(uris)
            pending = null
        }
    }

    suspend fun pick(): List<Pair<String, ByteArray>> {
        val l = launcher ?: return emptyList()
        val ctx = context ?: return emptyList()
        val wait = CompletableDeferred<List<Uri>>()
        pending?.complete(emptyList())
        pending = wait
        withContext(Dispatchers.Main) { l.launch(arrayOf("*/*")) }
        val uris = wait.await()
        return withContext(Dispatchers.IO) {
            uris.mapNotNull { uri ->
                runCatching {
                    val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: uri.lastPathSegment ?: "replay"
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                    name to bytes
                }.getOrNull()
            }
        }
    }
}
