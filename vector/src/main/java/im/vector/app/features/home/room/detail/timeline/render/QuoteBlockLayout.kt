/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.ViewCompat

/**
 * Draws [depth] blockquote stripes beside a block that was lifted out of the spannable (code block,
 * table), with the same geometry as QuoteMarginSpan so it lines up with the quoted text around it.
 */
class QuoteBlockLayout(context: Context) : LinearLayout(context) {

    private var depth = 0
    private var stripeWidth = 0
    private var margin = 0
    private val paint = Paint().apply { style = Paint.Style.FILL }

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
    }

    private val isRtl get() = ViewCompat.getLayoutDirection(this) == ViewCompat.LAYOUT_DIRECTION_RTL

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (level in 0 until depth) {
            val offset = (level * margin).toFloat()
            val left = if (isRtl) width - offset - stripeWidth else offset
            canvas.drawRect(left, 0f, left + stripeWidth, height.toFloat(), paint)
        }
    }

    companion object {
        fun wrap(block: View, depth: Int, stripeWidth: Int, margin: Int, stripeColor: Int): View {
            return QuoteBlockLayout(block.context).apply {
                this.depth = depth
                this.stripeWidth = stripeWidth
                this.margin = margin
                paint.color = stripeColor
                ViewCompat.setPaddingRelative(this, depth * margin, 0, 0, 0)
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(block)
            }
        }
    }
}
