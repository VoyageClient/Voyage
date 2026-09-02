/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.autocomplete

import android.content.Context
import androidx.recyclerview.widget.RecyclerView

/**
 * Caps the measured height at the first [maxVisibleItems] rows, so the rest scrolls. Rows are measured
 * rather than assumed uniform: an autocomplete row grows when its text wraps.
 */
class MaxVisibleItemsRecyclerView(context: Context, private val maxVisibleItems: Int) : RecyclerView(context) {

    // Measured with the spec we are given, never an unbounded one: a LinearLayoutManager fills whatever
    // space it is offered, so an "infinite" AT_MOST makes it inflate and measure every row in the adapter.
    // That is fine for a few dozen commands and an ANR for a room's whole member list.
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        if (childCount <= maxVisibleItems) return
        val manager = layoutManager ?: return
        var cap = paddingTop + paddingBottom
        for (i in 0 until maxVisibleItems) {
            cap += getChildAt(i)?.let { manager.getDecoratedMeasuredHeight(it) } ?: 0
        }
        setMeasuredDimension(measuredWidth, minOf(measuredHeight, cap))
    }
}
