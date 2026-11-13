package com.ygosandbox.app.combos

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A video ready to read: its frames plus its text. */
class PreparedVideo(val frames: List<Frame>, val description: String, val captions: String, val date: String)

/**
 * Everything read from one video so far, kept on the phone until the video is done. If the run
 * is cut short (Android closes the app, the connection drops, Stop), reading the same video
 * again skips the download and frame finding and the batches already read, so nothing is paid
 * for twice. Cleared once the video is added or left out; folders untouched for a week go.
 */
class VideoWork(root: File, url: String) {
    val dir = File(root, sha(url))
    private val framesDir = File(dir, "frames")
    private val framesDone = File(dir, "frames.done")
    private val meta = File(dir, "meta.json")

    init {
        dir.mkdirs()
        dir.setLastModified(System.currentTimeMillis())
        root.listFiles()?.filter { it != dir && System.currentTimeMillis() - it.lastModified() > 7L * 24 * 3600 * 1000 }
            ?.forEach { it.deleteRecursively() }
    }

    /** Downloads and cuts the video, or picks up what an earlier run already did. */
    suspend fun prepare(
        url: String,
        stage: String,
        downloadShare: Float,
        framesShare: Float,
        stepMs: Long,
        maxFrames: Int,
        progress: (String, Float) -> Unit,
    ): PreparedVideo {
        if (framesDone.exists() && meta.exists()) {
            val m = JSONObject(meta.readText())
            val frames = framesDir.listFiles { f -> f.name.startsWith("t") && f.name.endsWith(".jpg") }
                ?.sortedBy { it.name }
                ?.mapNotNull { f -> f.name.removePrefix("t").removeSuffix(".jpg").toLongOrNull()?.let { Frame(it / 10.0, f) } }
                ?: emptyList()
            if (frames.isNotEmpty()) {
                progress("$stage (from the last run)", downloadShare + framesShare)
                return PreparedVideo(frames, m.optString("description"), m.optString("captions"), m.optString("date"))
            }
        }
        progress("Downloading", 0f)
        val video = VideoSource.fetch(url, dir) { progress("Downloading", it * downloadShare) }
        var at = ""
        val frames = Frames.extract(
            video.file, framesDir, maxFrames = maxFrames, stepMs = stepMs,
            onPosition = { t, total -> at = " (${ComboViewModel.clock(t / 1000.0)} of ${ComboViewModel.clock(total / 1000.0)})" },
        ) { progress("$stage$at", downloadShare + it * framesShare) }
        video.file.delete()
        if (frames.isEmpty()) throw IllegalStateException("No frames could be read from the video")
        meta.writeText(JSONObject().put("description", video.description).put("captions", video.captions).put("date", video.date).toString())
        framesDone.createNewFile()
        return PreparedVideo(frames, video.description, video.captions, video.date)
    }

    /** A batch read before (its answer as JSON), or null. */
    fun batch(i: Int): JSONArray? = File(dir, "batch$i.json").takeIf { it.exists() }?.let { runCatching { JSONArray(it.readText()) }.getOrNull() }

    fun saveBatch(i: Int, events: JSONArray) = File(dir, "batch$i.json").writeText(events.toString())

    /** The video is finished (added or left out): nothing to resume. */
    fun clear() { dir.deleteRecursively() }

    private companion object {
        fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
    }
}
