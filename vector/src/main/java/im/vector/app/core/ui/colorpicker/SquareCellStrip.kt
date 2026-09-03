/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.colorpicker

import android.content.Context
import android.widget.LinearLayout

/**
 * A row of square cells that share the width, for previewing a palette entry by entry. The cells
 * shrink to fit as a palette grows, but stop at [maxCellPx] so a three-color palette does not turn
 * into a row of huge squares.
 */
class SquareCellStrip(context: Context, private val maxCellPx: Int) : LinearLayout(context) {

    init {
        orientation = HORIZONTAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cells = childCount.coerceAtLeast(1)
        val cell = minOf(MeasureSpec.getSize(widthMeasureSpec) / cells, maxCellPx)
        // An exact width of cell * cells leaves no remainder for the weights to hand out, so every
        // cell measures the same and matches the height.
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(cell * cells, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY),
        )
    }

    companion object {
        fun cellParams() = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
    }
}
