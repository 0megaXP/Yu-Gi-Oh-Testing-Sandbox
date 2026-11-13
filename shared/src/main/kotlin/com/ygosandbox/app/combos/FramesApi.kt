package com.ygosandbox.app.combos

import com.ygosandbox.app.platform.platformVideo
import java.io.File

/** One kept frame: when it is in the video and the JPEG on disk. */
data class Frame(val seconds: Double, val file: File)

/**
 * Cuts a video into the frames worth reading (one per screen change). The decoding is platform
 * code (Android's MediaMetadataRetriever); see the platform's [com.ygosandbox.app.platform.VideoPlatform].
 */
object Frames {
    suspend fun extract(
        video: File,
        dir: File,
        maxFrames: Int = 300,
        /** Milliseconds between sampled frames; whole games use a sparser step than combos. */
        stepMs: Long = 1000,
        /** Where it is in the video and the video's length, in milliseconds. */
        onPosition: (Long, Long) -> Unit = { _, _ -> },
        onProgress: (Float) -> Unit,
    ): List<Frame> = platformVideo().extractFrames(video, dir, maxFrames, stepMs, onPosition, onProgress)
}
