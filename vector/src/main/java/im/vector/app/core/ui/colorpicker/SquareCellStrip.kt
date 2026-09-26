/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.colorpicker

import android.annotation.SuppressLint
import android.content.Context
import android.widget.LinearLayout

// Constructed only in code because the size limit is supplied by the caller.
@SuppressLint("ViewConstructor")
class SquareCellStrip(context: Context, private val maxCellPx: Int) : LinearLayout(context) {

    init {
        orientation = HORIZONTAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cells = childCount.coerceAtLeast(1)
        val cell = minOf(MeasureSpec.getSize(widthMeasureSpec) / cells, maxCellPx)
        // Exclude remainder pixels so weighted cells stay square.
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(cell * cells, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY),
        )
    }

    companion object {
        fun cellParams() = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
    }
}
