package com.ygosandbox.app.combos

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** One kept frame: when it is in the video and the JPEG on disk. */
data class Frame(val seconds: Double, val file: File)

/**
 * Cuts a combo video into the frames worth reading: one per second, keeping a frame only when
 * the screen changed since the last one kept. Combo videos change the screen once per action,
 * so this leaves roughly one frame per step.
 */
object Frames {
    /** Bits (of 256) that must differ for a frame to count as a new screen. */
    private const val NEW_SCREEN = 10
    private const val WIDTH = 1280

    suspend fun extract(
        video: File,
        dir: File,
        maxFrames: Int = 300,
        /** Milliseconds between sampled frames; whole games use a sparser step than combos. */
        stepMs: Long = 1000,
        onProgress: (Float) -> Unit,
    ): List<Frame> = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(video.absolutePath)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw IllegalStateException("Couldn't read the video's length")
            val kept = ArrayList<Frame>()
            var lastHash: LongArray? = null
            // the last frame of the current screen, kept too if it moved on a little (an animation settling)
            var settled: Pair<Double, Bitmap>? = null
            var t = 0L
            while (t < ms) {
                coroutineContext.ensureActive()
                val raw = mmr.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                if (raw != null) {
                    val bmp = scale(raw)
                    val h = dhash(bmp)
                    val diff = lastHash?.let { distance(it, h) } ?: Int.MAX_VALUE
                    if (diff >= NEW_SCREEN) {
                        settled?.let { (s, b) -> kept += save(b, s, dir) }
                        kept += save(bmp, t / 1000.0, dir)
                        lastHash = h
                        settled = null
                    } else {
                        settled = if (diff >= 3) t / 1000.0 to bmp else null
                    }
                }
                onProgress(t.toFloat() / ms)
                t += stepMs
            }
            settled?.let { (s, b) -> kept += save(b, s, dir) }
            if (kept.size <= maxFrames) kept
            else {
                val step = kept.size.toDouble() / maxFrames
                val keep = (0 until maxFrames).map { kept[(it * step).toInt()] }.toSet()
                kept.filter { it !in keep }.forEach { it.file.delete() }
                kept.filter { it in keep }
            }
        } finally {
            mmr.release()
        }
    }

    private fun scale(b: Bitmap): Bitmap {
        if (b.width <= WIDTH) return b
        val h = (b.height.toLong() * WIDTH / b.width).toInt()
        return Bitmap.createScaledBitmap(b, WIDTH, h, true).also { if (it !== b) b.recycle() }
    }

    private fun save(b: Bitmap, seconds: Double, dir: File): Frame {
        val f = File(dir, "t%06d.jpg".format((seconds * 10).toLong()))
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        return Frame(seconds, f)
    }

    /** 256-bit difference hash: does each pixel of a 17x16 grey thumbnail get brighter to the right? */
    private fun dhash(b: Bitmap): LongArray {
        val small = Bitmap.createScaledBitmap(b, 17, 16, true)
        val out = LongArray(4)
        var bit = 0
        for (y in 0 until 16) {
            var prev = luma(small.getPixel(0, y))
            for (x in 1 until 17) {
                val cur = luma(small.getPixel(x, y))
                if (prev > cur) out[bit / 64] = out[bit / 64] or (1L shl (bit % 64))
                prev = cur
                bit++
            }
        }
        if (small !== b) small.recycle()
        return out
    }

    private fun luma(c: Int) = Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114

    private fun distance(a: LongArray, b: LongArray) = a.indices.sumOf { java.lang.Long.bitCount(a[it] xor b[it]) }
}
