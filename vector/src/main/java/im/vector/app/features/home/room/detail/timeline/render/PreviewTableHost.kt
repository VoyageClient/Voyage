/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.render

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TableLayout

/**
 * Hosts a table in a non-interactive preview (reply header / composer / long-press sheet), where
 * there is no scroll view. Measures the table at its natural size and clips the overflow: measuring
 * with the preview's constrained spec instead caps one column at the full available width while
 * starving the rest, and a capped height squashes the trailing rows to nothing — together rendering
 * the preview as a single stretched header row. With line wrapping enabled, a mildly-overflowing
 * table still shrinks to fit, mirroring [ShrinkableHorizontalScrollView].
 */
class PreviewTableHost(context: Context) : ViewGroup(context) {

    var allowShrink = false

    private val shrinkFactor = 0.25f
    private var lastAvailable = -1
    private var lastNatural = -1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child: View? = if (childCount > 0) getChildAt(0) else null
        if (child == null) {
            setMeasuredDimension(0, 0)
            return
        }
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val table = child as? TableLayout
        if (lastNatural < 0 || available != lastAvailable) {
            table?.isShrinkAllColumns = false
            child.measure(unspecified, unspecified)
            lastNatural = child.measuredWidth
            lastAvailable = available
        }
        val shrink = table != null && allowShrink &&
                lastNatural > available && lastNatural <= (available / (1f - shrinkFactor)).toInt()
        table?.isShrinkAllColumns = shrink
        child.measure(if (shrink) MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST) else unspecified, unspecified)
        setMeasuredDimension(minOf(child.measuredWidth, available), child.measuredHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val child: View = if (childCount > 0) getChildAt(0) else return
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
    }
}
