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
import com.airbnb.epoxy.EpoxyModel
import com.airbnb.epoxy.EpoxyViewHolder
import im.vector.app.features.themes.ThemeUtils

/**
 * Draws a 1px separator under every row except the last. Used instead of divider epoxy models so that
 * drag-reordering only moves item models (no divider insert/remove), which keeps the scroll position stable.
 * A matching item offset reserves the line's height so opaque item backgrounds don't paint over it.
 */
class ListDividerDecoration(
        context: Context,
        /**
         * Which rows get a separator under them. Defaults to every row but the last; pass a predicate to
         * separate only part of a mixed list.
         */
        private val drawUnder: ((EpoxyModel<*>) -> Boolean)? = null,
        /**
         * Whether a separator follows its row as it is dragged, rather than staying at the layout gap.
         */
        private val followItemTranslation: Boolean = false,
) : RecyclerView.ItemDecoration() {

    private val paint = Paint().apply {
        color = ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_list_separator)
    }
    private val thickness = context.resources.displayMetrics.density.coerceAtLeast(1f)

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.bottom = if (hasSeparator(parent, view)) thickness.toInt() else 0
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val left = parent.paddingLeft.toFloat()
        val right = (parent.width - parent.paddingRight).toFloat()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (!hasSeparator(parent, child)) continue
            val top = child.bottom + if (followItemTranslation) child.translationY else 0f
            canvas.drawRect(left, top, right, top + thickness, paint)
        }
    }

    private fun hasSeparator(parent: RecyclerView, child: View): Boolean {
        val position = parent.getChildAdapterPosition(child)
        if (position == RecyclerView.NO_POSITION) return false
        val predicate = drawUnder ?: return position < (parent.adapter?.itemCount ?: 0) - 1
        val model = (parent.getChildViewHolder(child) as? EpoxyViewHolder)?.model ?: return false
        return predicate(model)
    }
}
