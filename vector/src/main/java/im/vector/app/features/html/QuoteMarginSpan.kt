/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.style.LeadingMarginSpan
import androidx.core.graphics.ColorUtils

/**
 * Blockquote stripe + indent. Geometry mirrors the reply preview bar (see view_in_reply_to.xml) so a
 * blockquote lines up with the reply quote of the same message instead of indenting further.
 */
class QuoteMarginSpan(
        private val stripeWidth: Int,
        private val margin: Int,
) : LeadingMarginSpan {

    private val rect = Rect()
    private val paint = Paint()

    override fun getLeadingMargin(first: Boolean) = margin

    override fun drawLeadingMargin(
            c: Canvas, p: Paint, x: Int, dir: Int,
            top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int,
            first: Boolean, layout: Layout?,
    ) {
        paint.set(p)
        paint.style = Paint.Style.FILL
        paint.color = ColorUtils.setAlphaComponent(p.color, BLOCK_QUOTE_ALPHA)
        val edge = x + dir * stripeWidth
        rect.set(minOf(x, edge), top, maxOf(x, edge), bottom)
        c.drawRect(rect, paint)
    }

    companion object {
        // Matches Markwon's default blockquote stripe translucency.
        private const val BLOCK_QUOTE_ALPHA = 25
    }
}
