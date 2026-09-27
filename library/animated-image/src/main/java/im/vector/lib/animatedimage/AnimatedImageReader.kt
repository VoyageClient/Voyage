/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.animatedimage

import timber.log.Timber
import java.io.File

/** Reads any of the animated image formats into a plain sequence of complete frames. */
object AnimatedImageReader {

    /** @return null when the file is not an animation, or cannot be decoded. */
    fun readFrames(file: File, format: AnimatedImageFormat? = null): List<AnimatedFrame>? {
        return when (format ?: AnimatedImageFormat.detect(file)) {
            AnimatedImageFormat.GIF -> GifFrameReader.readFrames(file)
            AnimatedImageFormat.APNG -> ApngFrameReader.readFrames(file)
            AnimatedImageFormat.WEBP -> AnimatedWebpReader.readFrames(file)
            AnimatedImageFormat.JXL -> JxlFrameReader.readFrames(file)
            null -> null
        }
    }

    // The bitmap belongs to the reader and is only valid during the callback.
    fun visitFrames(file: File, format: AnimatedImageFormat? = null, onFrame: (AnimatedFrame, Int, Int) -> Unit): Boolean =
            when (format ?: AnimatedImageFormat.detect(file)) {
                AnimatedImageFormat.GIF -> GifFrameReader.visitFrames(file, onFrame)
                AnimatedImageFormat.APNG -> ApngFrameReader.visitFrames(file, onFrame)
                AnimatedImageFormat.WEBP -> AnimatedWebpReader.visitFrames(file, onFrame)
                AnimatedImageFormat.JXL -> JxlFrameReader.visitFrames(file, onFrame)
                null -> false
            }
}

internal fun collectFrames(read: ((AnimatedFrame, Int, Int) -> Unit) -> Boolean): List<AnimatedFrame>? {
    val frames = ArrayList<AnimatedFrame>()
    try {
        val decoded = read { frame, _, _ ->
            val bitmap = frame.bitmap.copy(frame.bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)
            frames.add(AnimatedFrame(bitmap, frame.durationMs))
        }
        if (decoded && frames.isNotEmpty()) return frames
    } catch (error: Throwable) {
        Timber.w(error, "Animated: cannot collect frames")
    }
    frames.forEach { it.bitmap.recycle() }
    return null
}
