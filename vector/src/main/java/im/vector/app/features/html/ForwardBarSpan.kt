/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.style.LeadingMarginSpan

/** The bar down the start edge of a forward, for previews that show one inside a single text view. */
class ForwardBarSpan(
        private val barWidth: Int,
        private val gap: Int,
        private val color: Int,
) : LeadingMarginSpan {

    private val paint = Paint().apply { style = Paint.Style.FILL }

    override fun getLeadingMargin(first: Boolean) = barWidth + gap

    override fun drawLeadingMargin(
            c: Canvas, p: Paint, x: Int, dir: Int,
            top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int,
            first: Boolean, layout: Layout?,
    ) {
        paint.color = color
        val edge = x + dir * barWidth
        c.drawRect(minOf(x, edge).toFloat(), top.toFloat(), maxOf(x, edge).toFloat(), bottom.toFloat(), paint)
    }
}
