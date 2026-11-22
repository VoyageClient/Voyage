/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.attachmentviewer

import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.TextureView

object VideoFramePreview {

    fun capture(view: TextureView, videoWidth: Float, videoHeight: Float, rotation: Int, maxDimension: Int = 512): Bitmap? {
        if (videoWidth <= 0f || videoHeight <= 0f || maxDimension <= 0) return null
        val scale = minOf(1f, maxDimension / maxOf(videoWidth, videoHeight))
        // TextureView's bitmap copy omits the aspect-fit and rotation transform used on screen.
        val bitmap = view.getBitmap((videoWidth * scale).toInt().coerceAtLeast(1), (videoHeight * scale).toInt().coerceAtLeast(1))
                ?: return null
        if (rotation % 360 == 0) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    }
}
