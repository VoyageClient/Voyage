/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.animatedimage

import android.os.Build
import com.awxkee.jxlcoder.JxlAnimatedImage
import com.awxkee.jxlcoder.JxlResizeFilter
import com.awxkee.jxlcoder.JxlToneMapper
import com.awxkee.jxlcoder.PreferredColorConfig
import com.awxkee.jxlcoder.ScaleMode
import timber.log.Timber
import java.io.File

/**
 * libjxl declares minSdk 21 and loads its .so from a static initialiser, so every entry point checks
 * [isAvailable] first and nothing outside this class touches the library.
 */
internal object JxlFrameReader {

    val isAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP

    /** @return the frame count, or null when the bytes are not a readable JPEG XL. */
    fun frameCount(bytes: ByteArray): Int? {
        if (!isAvailable) return null
        return try {
            open(bytes).use { it.numberOfFrames }
        } catch (t: Throwable) {
            Timber.w(t, "JXL: cannot read frame count")
            null
        }
    }

    fun readFrames(file: File): List<AnimatedFrame>? = collectFrames { visitFrames(file, null, it) }

    fun visitFrames(file: File, onHeader: FrameHeader?, onFrame: (AnimatedFrame, Int, Int) -> Unit): Boolean {
        if (!isAvailable) return false
        val animation = try {
            open(file.readBytes())
        } catch (error: Throwable) {
            Timber.w(error, "JXL: cannot open source")
            return false
        }
        animation.use {
            val count = it.numberOfFrames
            if (count <= 0) return false
            val width = it.getWidth()
            val height = it.getHeight()
            val durations = IntArray(count) { index -> it.getFrameDuration(index).coerceAtLeast(MIN_FRAME_DELAY_MS) }
            val scale = onHeader?.invoke(width, height, durations)?.takeIf { scale -> scale < 1f }
            // 0 x 0 asks for the frame at its own size.
            val frameWidth = scale?.let { s -> (width * s).toInt().coerceAtLeast(1) } ?: 0
            val frameHeight = scale?.let { s -> (height * s).toInt().coerceAtLeast(1) } ?: 0
            for (index in 0 until count) {
                val bitmap = it.getFrame(index, frameWidth, frameHeight)
                try {
                    onFrame(AnimatedFrame(bitmap, durations[index]), index, count)
                } finally {
                    bitmap.recycle()
                }
            }
            return true
        }
    }

    private fun open(bytes: ByteArray) = JxlAnimatedImage(
            bytes,
            PreferredColorConfig.DEFAULT,
            ScaleMode.FIT,
            JxlResizeFilter.BILINEAR,
            JxlToneMapper.REC2408,
    )
}
