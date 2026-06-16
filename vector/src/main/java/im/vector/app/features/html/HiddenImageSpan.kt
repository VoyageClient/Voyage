/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan

/**
 * Inline placeholder for a hidden HTML <img>/emoticon: a small rounded grey box sized to the line
 * height, drawn instead of fetching the image while media is hidden. No "show" label — these are
 * emote-sized; revealing is handled at the message level (tap to reveal).
 */
class HiddenImageSpan(private val fillColor: Int) : ReplacementSpan() {

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val fontHeight = paint.fontMetricsInt.let { it.descent - it.ascent }
        return (fontHeight * BOX_WIDTH_RATIO).toInt()
    }

    override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
    ) {
        val fm = paint.fontMetricsInt
        val boxTop = (y + fm.ascent).toFloat()
        val boxBottom = (y + fm.descent).toFloat()
        val boxWidth = (boxBottom - boxTop) * BOX_WIDTH_RATIO
        val radius = (boxBottom - boxTop) * CORNER_RATIO
        val rect = RectF(x, boxTop, x + boxWidth, boxBottom)
        val previousColor = paint.color
        paint.color = fillColor
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.color = previousColor
    }

    companion object {
        private const val BOX_WIDTH_RATIO = 1f
        private const val CORNER_RATIO = 0.15f
    }
}
