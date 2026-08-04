/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Guides drawn over the crop window: its border, and rule-of-thirds lines inside it. */
class VideoCropFrameView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = BORDER_WIDTH_DP * density
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(GRID_ALPHA, 255, 255, 255)
        strokeWidth = GRID_WIDTH_DP * density
    }

    override fun onDraw(canvas: Canvas) {
        val inset = borderPaint.strokeWidth / 2f
        canvas.drawRect(inset, inset, width - inset, height - inset, borderPaint)
        for (index in 1 until THIRDS) {
            val x = width * index / THIRDS.toFloat()
            val y = height * index / THIRDS.toFloat()
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
        }
    }

    companion object {
        private const val BORDER_WIDTH_DP = 2f
        private const val GRID_WIDTH_DP = 1f
        private const val GRID_ALPHA = 110
        private const val THIRDS = 3
    }
}
