/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import android.graphics.Bitmap
import com.awxkee.jxlcoder.JxlAnimatedImage
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.JxlResizeFilter
import com.awxkee.jxlcoder.JxlToneMapper
import com.awxkee.jxlcoder.PreferredColorConfig
import com.awxkee.jxlcoder.ScaleMode
import im.vector.lib.animatedimage.AnimatedFrame
import timber.log.Timber
import java.io.File

/**
 * Every libjxl entry point the upload path uses. Constructing nothing here is safe below API 21 —
 * the class must only be reached through [org.matrix.android.sdk.api.util.JxlSupport].
 */
internal object JxlImageReader {

    fun readSize(file: File): Pair<Int, Int>? {
        return try {
            JxlCoder.getSize(file.readBytes())?.let { it.width to it.height }
        } catch (t: Throwable) {
            Timber.w(t, "Unable to read JPEG XL dimensions")
            null
        }
    }

    /** Frame count, or null if the file can't be opened. 1 for a still image. */
    fun frameCount(file: File): Int? {
        return try {
            JxlAnimatedImage(
                    file.readBytes(),
                    PreferredColorConfig.DEFAULT,
                    ScaleMode.FIT,
                    JxlResizeFilter.BILINEAR,
                    JxlToneMapper.REC2408,
            ).use { it.numberOfFrames }
        } catch (t: Throwable) {
            Timber.w(t, "Unable to read JPEG XL frame count")
            null
        }
    }

    /** Every frame with its duration, for re-encoding an animation into a format others can read. */
    fun readFrames(file: File): List<AnimatedFrame>? {
        return try {
            JxlAnimatedImage(
                    file.readBytes(),
                    PreferredColorConfig.DEFAULT,
                    ScaleMode.FIT,
                    JxlResizeFilter.BILINEAR,
                    JxlToneMapper.REC2408,
            ).use { image ->
                (0 until image.numberOfFrames).map { index ->
                    AnimatedFrame(
                            bitmap = image.getFrame(index),
                            durationMs = image.getFrameDuration(index),
                    )
                }
            }.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            Timber.w(t, "Unable to read JPEG XL frames")
            null
        }
    }

    /** Decodes bounded by [maxDimension] on the longer side, preserving aspect. */
    fun decode(file: File, maxDimension: Int): Bitmap? {
        return try {
            val bytes = file.readBytes()
            val size = JxlCoder.getSize(bytes) ?: return null
            val longest = maxOf(size.width, size.height)
            val scale = if (longest > maxDimension) maxDimension.toFloat() / longest else 1f
            JxlCoder.decodeSampled(
                    bytes,
                    (size.width * scale).toInt().coerceAtLeast(1),
                    (size.height * scale).toInt().coerceAtLeast(1),
                    PreferredColorConfig.DEFAULT,
                    ScaleMode.FIT,
                    JxlResizeFilter.BILINEAR,
                    JxlToneMapper.REC2408,
            )
        } catch (t: Throwable) {
            Timber.w(t, "Unable to decode JPEG XL")
            null
        }
    }
}
