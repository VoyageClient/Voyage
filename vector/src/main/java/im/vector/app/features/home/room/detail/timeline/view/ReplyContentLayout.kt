/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isGone
import im.vector.app.R
import kotlin.math.max
import kotlin.math.min

/**
 * Message content column as wide as the wider of its reply header and its content. Under a non-exact
 * width, a stock LinearLayout sizes to its wrap_content children only, so the header would cap the rest.
 */
class ReplyContentLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private var widestFillChild = 0

    init {
        orientation = VERTICAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        widestFillChild = 0
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        if (mode == MeasureSpec.EXACTLY || !onlyReplyHeaderWraps()) return
        var wanted = max(widestFillChild + paddingLeft + paddingRight, suggestedMinimumWidth)
        if (mode == MeasureSpec.AT_MOST) wanted = min(wanted, MeasureSpec.getSize(widthMeasureSpec))
        if (wanted > measuredWidth) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(wanted, MeasureSpec.EXACTLY), heightMeasureSpec)
        }
    }

    // Only the first, non-exact pass reports a match_parent child's natural width.
    override fun measureChildWithMargins(child: View, parentWidthMeasureSpec: Int, widthUsed: Int, parentHeightMeasureSpec: Int, heightUsed: Int) {
        super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed, parentHeightMeasureSpec, heightUsed)
        val lp = child.layoutParams as MarginLayoutParams
        if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT && MeasureSpec.getMode(parentWidthMeasureSpec) != MeasureSpec.EXACTLY) {
            widestFillChild = max(widestFillChild, child.measuredWidth + lp.leftMargin + lp.rightMargin)
        }
    }

    // Any other wrap_content child (the plain text body) keeps setting the column width.
    private fun onlyReplyHeaderWraps(): Boolean {
        var header = false
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.isGone || child.layoutParams.width == ViewGroup.LayoutParams.MATCH_PARENT) continue
            if (child.id != R.id.inReplyToContainer) return false
            header = true
        }
        return header
    }
}
