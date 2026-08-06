/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.style.ReplacementSpan

/**
 * Inline icon centred on the text, sized relative to it — what a compound drawable looks like on a
 * single-line TextView, but usable mid-text and on a view taller than one line (where a compound
 * drawable centres on the whole view instead).
 *
 * Line metrics are deliberately left alone: the box is smaller than the text's own ascent+descent, so
 * reserving space for it would only shrink the line.
 */
class CenteredIconSpan(
        private val drawable: Drawable,
        private val gapAfter: Float = 0f,
) : ReplacementSpan() {

    private fun boxSize(paint: Paint) = paint.textSize * 1.1f

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        return (boxSize(paint) + gapAfter).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val box = boxSize(paint)
        val centerY = y + (paint.fontMetricsInt.ascent + paint.fontMetricsInt.descent) / 2f
        drawable.setBounds(0, 0, box.toInt(), box.toInt())
        canvas.save()
        canvas.translate(x, centerY - box / 2f)
        drawable.draw(canvas)
        canvas.restore()
    }
}
