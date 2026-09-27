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

    fun readFrames(file: File): List<AnimatedFrame>? = collectFrames { visitFrames(file, it) }

    fun visitFrames(file: File, onFrame: (AnimatedFrame, Int, Int) -> Unit): Boolean {
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
            for (index in 0 until count) {
                val bitmap = it.getFrame(index, 0, 0)
                try {
                    onFrame(AnimatedFrame(bitmap, it.getFrameDuration(index).coerceAtLeast(MIN_FRAME_DELAY_MS)), index, count)
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
