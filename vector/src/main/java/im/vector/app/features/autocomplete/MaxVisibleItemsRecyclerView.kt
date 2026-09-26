/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.autocomplete

import android.annotation.SuppressLint
import android.content.Context
import androidx.recyclerview.widget.RecyclerView

// Constructed only in code because the size limit is supplied by the caller.
@SuppressLint("ViewConstructor")
class MaxVisibleItemsRecyclerView(context: Context, private val maxVisibleItems: Int) : RecyclerView(context) {

    // An unbounded spec makes LinearLayoutManager measure the entire member list and can cause an ANR.
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
