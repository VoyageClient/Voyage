/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.epoxy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import im.vector.app.features.themes.ThemeUtils

/**
 * Draws a 1px separator under every row except the last. Used instead of divider epoxy models so that
 * drag-reordering only moves item models (no divider insert/remove), which keeps the scroll position stable.
 * A matching item offset reserves the line's height so opaque item backgrounds don't paint over it.
 */
class ListDividerDecoration(context: Context) : RecyclerView.ItemDecoration() {

    private val paint = Paint().apply {
        color = ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_list_separator)
    }
    private val thickness = context.resources.displayMetrics.density.toInt().coerceAtLeast(1)

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        val lastDrawablePosition = (parent.adapter?.itemCount ?: 0) - 1
        outRect.bottom = if (position != RecyclerView.NO_POSITION && position < lastDrawablePosition) thickness else 0
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val left = parent.paddingLeft.toFloat()
        val right = (parent.width - parent.paddingRight).toFloat()
        val lastDrawablePosition = (parent.adapter?.itemCount ?: 0) - 1
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION || position >= lastDrawablePosition) continue
            // Draw at the static layout gap (no translationY): during a drag the separators stay put as part
            // of the list while the dragged item floats over them, instead of one lone line tracking the item.
            val top = child.bottom
            canvas.drawRect(left, top.toFloat(), right, (top + thickness).toFloat(), paint)
        }
    }
}
