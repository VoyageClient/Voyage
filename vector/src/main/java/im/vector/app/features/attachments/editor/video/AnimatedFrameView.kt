/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.withMatrix
import kotlin.math.min

/**
 * Shows an animated image's current frame, stretched to fill the view as the video surface is, then
 * moved by the crop overlay's transform. Drawn like the media viewer's zoomable image rather than
 * into a view-sized surface, so a zoom is only a new matrix and shows the frame's own pixels.
 */
class AnimatedFrameView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destination = Rect()
    private val transform = Matrix()

    var frame: Bitmap? = null
        set(value) {
            if (field === value) return
            field = value
            invalidate()
        }

    /** The largest side a frame may have and still be drawn; a hardware canvas refuses anything bigger. */
    var maxBitmapSize = DEFAULT_MAX_BITMAP_SIZE
        private set

    fun setTransform(matrix: Matrix) {
        transform.set(matrix)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        maxBitmapSize = min(canvas.maximumBitmapWidth, canvas.maximumBitmapHeight)
        val bitmap = frame?.takeIf { !it.isRecycled } ?: return
        destination.set(0, 0, width, height)
        canvas.withMatrix(transform) { drawBitmap(bitmap, null, destination, paint) }
    }

    private companion object {
        /** What the oldest GPUs this fork runs on accept, until a draw reports the real limit. */
        const val DEFAULT_MAX_BITMAP_SIZE = 2048
    }
}
