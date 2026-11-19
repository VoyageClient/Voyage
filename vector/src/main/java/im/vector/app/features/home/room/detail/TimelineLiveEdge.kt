/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail

import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.EpoxyViewHolder
import im.vector.app.R
import im.vector.app.features.home.room.detail.timeline.item.ItemWithEvents

/**
 * Whether the reader has reached the end of the newest message: at least half of its final line is on
 * screen. Being scrolled anywhere onto a tall message is not enough, or the timeline would yank someone
 * reading its start down to whatever arrives next.
 */
object TimelineLiveEdge {

    // Matches the fixed lineHeight of Widget.Vector.TextView.Body.
    private const val FALLBACK_LINE_SP = 20f

    fun isAtLiveEdge(recyclerView: RecyclerView, layoutManager: LinearLayoutManager): Boolean {
        val first = layoutManager.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return true
        // Position 0 is the typing spacer, so the newest message is at 0 or 1.
        if (first > 1) return false
        val newest = newestEventRow(recyclerView) ?: return true
        val line = finalLineBounds(recyclerView, newest) ?: return true
        return line.centerY() <= recyclerView.height - recyclerView.paddingBottom
    }

    // Layout positions lag adapter positions between a diff and the next layout, so go by what is on screen.
    private fun newestEventRow(recyclerView: RecyclerView): View? {
        var newest: View? = null
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val model = (recyclerView.getChildViewHolder(child) as? EpoxyViewHolder)?.model
            if (model !is ItemWithEvents) continue
            if (newest == null || child.bottom > newest.bottom) newest = child
        }
        return newest
    }

    private fun finalLineBounds(recyclerView: RecyclerView, row: View): Rect? {
        row.findShown<TextView>(R.id.messageTextView)?.let { return lastLine(recyclerView, it) }
        row.findShown<ViewGroup>(R.id.richBodyContainer)?.let { container ->
            val lastBlock = (container.childCount - 1 downTo 0)
                    .map { container.getChildAt(it) }
                    .firstOrNull { it.isVisible && it.height > 0 }
            when (lastBlock) {
                is TextView -> return lastLine(recyclerView, lastBlock)
                null -> Unit
                else -> return bottomBand(recyclerView, lastBlock)
            }
        }
        val content = row.findShown<View>(R.id.viewStubContainer) ?: row
        return bottomBand(recyclerView, content)
    }

    private fun lastLine(recyclerView: RecyclerView, textView: TextView): Rect {
        val layout = textView.layout
        if (layout == null || layout.lineCount == 0) return bottomBand(recyclerView, textView)
        val last = layout.lineCount - 1
        val top = textView.totalPaddingTop + layout.getLineTop(last)
        val bottom = textView.totalPaddingTop + layout.getLineBottom(last)
        return Rect(0, top, textView.width, bottom).also { recyclerView.offsetDescendantRectToMyCoords(textView, it) }
    }

    private fun bottomBand(recyclerView: RecyclerView, view: View): Rect {
        val line = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, FALLBACK_LINE_SP, view.resources.displayMetrics).toInt()
        return Rect(0, (view.height - line).coerceAtLeast(0), view.width, view.height)
                .also { recyclerView.offsetDescendantRectToMyCoords(view, it) }
    }

    private inline fun <reified T : View> View.findShown(id: Int): T? {
        val view = findViewById<View>(id) ?: return null
        if (view.height == 0) return null
        var current: View? = view
        while (current != null && current !== this) {
            if (!current.isVisible) return null
            current = current.parent as? View
        }
        return view as? T
    }
}
