/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/** When it has to scroll, shrinks to whole rows so it never ends on a half-cut one. Assumes equal row heights. */
class WholeRowsRecyclerView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
) : RecyclerView(context, attrs) {

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        // Auto-measure has already laid the rows out, so the first one has its real height.
        val rowHeight = getChildAt(0)?.measuredHeight?.takeIf { it > 0 } ?: return
        val itemCount = adapter?.itemCount ?: return
        val inner = measuredHeight - paddingTop - paddingBottom
        if (itemCount * rowHeight <= inner) return
        val rows = (inner / rowHeight).coerceAtLeast(1)
        setMeasuredDimension(measuredWidth, paddingTop + rows * rowHeight + paddingBottom)
    }
}
