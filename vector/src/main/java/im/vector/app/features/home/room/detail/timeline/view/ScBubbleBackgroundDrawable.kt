/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.view

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Code-drawn SchildiChat message bubble (rounded body + tail). Pre-21 a layer-list can't position or
 * size the tail (item gravity/size is API 23+), so the XML tail rendered as a fixed square; drawing it
 * ourselves lets the tail be the intended height on KitKat.
 */
class ScBubbleBackgroundDrawable(
        private val fillColor: Int,
        private val cornerRadius: Float,
        private val tailWidth: Float,
        private val tailHeight: Float,
        private val tailOnRight: Boolean,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }
    private val path = Path()

    override fun onBoundsChange(bounds: Rect) {
        rebuildPath(bounds)
    }

    private fun rebuildPath(b: Rect) {
        path.reset()
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val r = cornerRadius
        val bodyLeft = if (tailOnRight) 0f else tailWidth
        val bodyRight = if (tailOnRight) w - tailWidth else w
        // Square the top corner on the tail side (matching the XML bubbles), round the other three.
        val topLeft = if (tailOnRight) r else 0f
        val topRight = if (tailOnRight) 0f else r
        val radii = floatArrayOf(topLeft, topLeft, topRight, topRight, r, r, r, r)
        path.addRoundRect(RectF(bodyLeft, 0f, bodyRight, h), radii, Path.Direction.CW)
        if (tailOnRight) {
            path.moveTo(bodyRight, 0f)
            path.lineTo(bodyRight + tailWidth, 0f)
            path.lineTo(bodyRight, tailHeight)
        } else {
            path.moveTo(bodyLeft, 0f)
            path.lineTo(bodyLeft - tailWidth, 0f)
            path.lineTo(bodyLeft, tailHeight)
        }
        path.close()
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
