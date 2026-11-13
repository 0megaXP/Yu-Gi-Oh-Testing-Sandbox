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
    private var treeLauncher: ActivityResultLauncher<Uri?>? = null
    private var pendingTree: CompletableDeferred<Uri?>? = null

    fun install(activity: ComponentActivity) {
        context = activity.applicationContext
        launcher = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            pending?.complete(uris)
            pending = null
        }
        treeLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            pendingTree?.complete(uri)
            pendingTree = null
        }
    }

    /** A folder the person grants lasting read access to, as its tree URI. */
    suspend fun pickFolder(): String? {
        val l = treeLauncher ?: return null
        val ctx = context ?: return null
        val wait = CompletableDeferred<Uri?>()
        pendingTree?.complete(null)
        pendingTree = wait
        withContext(Dispatchers.Main) { l.launch(null) }
        val uri = wait.await() ?: return null
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        return uri.toString()
    }

    suspend fun readFolder(folder: String, extension: String, accept: (String, Long) -> Boolean): List<Pair<String, ByteArray>> =
        withContext(Dispatchers.IO) {
            val ctx = context ?: return@withContext emptyList()
            val tree = Uri.parse(folder)
            if (tree.scheme != "content") error("Choose the folder with Browse on the phone")
            val out = ArrayList<Pair<String, ByteArray>>()
            fun walk(docId: String, depth: Int) {
                val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                val cols = arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                    android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                )
                ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0); val name = c.getString(1) ?: continue
                        val mime = c.getString(2); val modified = c.getLong(3)
                        if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (depth < 3) walk(id, depth + 1)
                        } else if (name.endsWith(extension, ignoreCase = true) && accept(name, modified)) {
                            val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()?.let { out += name to it }
                        }
                    }
                }
            }
            walk(android.provider.DocumentsContract.getTreeDocumentId(tree), 0)
            out
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
