/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import android.graphics.Bitmap
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.JxlResizeFilter
import com.awxkee.jxlcoder.JxlToneMapper
import com.awxkee.jxlcoder.PreferredColorConfig
import com.awxkee.jxlcoder.ScaleMode
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
