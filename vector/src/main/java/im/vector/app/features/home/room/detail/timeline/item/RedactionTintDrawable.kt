/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * The two deleted-content marks a timeline row can carry, painted on one full-row view: a wash over the
 * whole row when the row itself is showing recovered content, and a band over the vertical slice its reply
 * header occupies when *that* is showing recovered content.
 *
 * A drawable rather than a pair of sized sibling views, because the band's extent is only known once the
 * reply header has laid out — and by then a `layoutParams` change is unreliable: RecyclerView swallows
 * `requestLayout()` from a child during its own layout/draw pass, so the band stayed invisible until an
 * unrelated scroll happened to trigger another one.
 */
class RedactionTintDrawable : Drawable() {

    private val paint = Paint()

    private var rowColor: Int = Color.TRANSPARENT
    private var bandColor: Int = Color.TRANSPARENT
    private var bandTop = 0
    private var bandBottom = 0

    fun setRow(color: Int) {
        if (rowColor == color) return
        rowColor = color
        invalidateSelf()
    }

    /** [top] and [bottom] are offsets from the top of the row. */
    fun setBand(color: Int, top: Int, bottom: Int) {
        if (bandColor == color && bandTop == top && bandBottom == bottom) return
        bandColor = color
        bandTop = top
        bandBottom = bottom
        invalidateSelf()
    }

    fun clearBand() = setBand(Color.TRANSPARENT, 0, 0)

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (rowColor != Color.TRANSPARENT) {
            paint.color = rowColor
            canvas.drawRect(bounds, paint)
        }
        if (bandColor != Color.TRANSPARENT && bandBottom > bandTop) {
            paint.color = bandColor
            canvas.drawRect(
                    bounds.left.toFloat(),
                    (bounds.top + bandTop).toFloat(),
                    bounds.right.toFloat(),
                    (bounds.top + bandBottom).toFloat(),
                    paint
            )
        }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
