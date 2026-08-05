/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlin.math.roundToInt

/**
 * How hard an attachment is squeezed on the way out. It belongs to the preview screen rather than
 * an editor because it is about the upload, not the picture, and applies to images and videos
 * alike — the SDK's existing compressors act on it at send time.
 *
 * @property quality 0..100, where [STANDARD_QUALITY] is what the automatic pass would have done.
 * @property width,height output size. Always both or neither: the compressors need a complete
 * pair, and a half-specified size silently did nothing.
 * @property linked whether typing one dimension moves the other to preserve the source's shape.
 */
@Parcelize
data class CompressionSettings(
        val quality: Int = STANDARD_QUALITY,
        val width: Int? = null,
        val height: Int? = null,
        val linked: Boolean = true,
) : Parcelable {

    val isDefault: Boolean get() = quality == STANDARD_QUALITY && width == null && height == null

    /** @param aspect source width over height, used to move the dimension the user did not type. */
    fun withWidth(value: Int, aspect: Float) =
            copy(width = value, height = if (linked) (value / aspect).roundToInt().coerceAtLeast(1) else height)

    fun withHeight(value: Int, aspect: Float) =
            copy(height = value, width = if (linked) (value * aspect).roundToInt().coerceAtLeast(1) else width)

    /** Drops a size that matches the source, so an untouched pair of boxes is not a resize request. */
    fun withoutRedundantSize(sourceWidth: Int, sourceHeight: Int): CompressionSettings {
        val unchanged = (width ?: sourceWidth) == sourceWidth && (height ?: sourceHeight) == sourceHeight
        return if (unchanged) copy(width = null, height = null) else this
    }

    companion object {
        const val MAX_QUALITY = 100

        /** Reproduces what compression did before the slider existed — see UploadContentWorker. */
        const val STANDARD_QUALITY = 70
    }
}
