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

    fun readFrames(file: File): List<AnimatedFrame>? {
        if (!isAvailable) return null
        val bytes = try {
            file.readBytes()
        } catch (t: Throwable) {
            Timber.w(t, "JXL: cannot read source")
            return null
        }
        return try {
            open(bytes).use { animation ->
                val count = animation.numberOfFrames
                if (count <= 0) return null
                val out = ArrayList<AnimatedFrame>(count)
                for (index in 0 until count) {
                    val bitmap = animation.getFrame(index, 0, 0)
                    val delay = animation.getFrameDuration(index).coerceAtLeast(MIN_FRAME_DELAY_MS)
                    out.add(AnimatedFrame(bitmap, delay))
                }
                out.takeIf { it.isNotEmpty() }
            }
        } catch (t: Throwable) {
            Timber.w(t, "JXL: cannot read frames")
            null
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
