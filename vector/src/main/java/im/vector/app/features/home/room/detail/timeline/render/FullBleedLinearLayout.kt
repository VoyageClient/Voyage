/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.render

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/**
 * A LinearLayout that, when [fullBleed] is set, stretches to the full available width even inside a
 * wrap_content ancestor. Used so a code block fills the row in the non-bubble timeline (where the
 * message container hugs its content) instead of shrinking to the longest code line.
 */
class FullBleedLinearLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var fullBleed = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (!fullBleed) return
        val mode = View.MeasureSpec.getMode(widthMeasureSpec)
        val size = View.MeasureSpec.getSize(widthMeasureSpec)
        if (mode != View.MeasureSpec.UNSPECIFIED && size > 0 && measuredWidth < size) {
            // Re-measure at the full width so the weighted code scroll fills it (weight only distributes
            // space in an EXACTLY spec), expanding the surrounding wrap_content container to the edge.
            super.onMeasure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), heightMeasureSpec)
        }
    }
}
