/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.util.AttributeSet
import android.widget.RelativeLayout

/**
 * Timeline row root. Drops the focused/selected/activated states a held text selection merges up
 * through addStatesFromChildren, which would otherwise sit as a steady selectableItemBackground
 * veil for the selection's whole lifetime. Pressed still comes through, so taps ripple.
 */
class SelectionAwareRelativeLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
) : RelativeLayout(context, attrs, defStyleAttr) {

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        return super.onCreateDrawableState(extraSpace).filter {
            it != android.R.attr.state_focused &&
                    it != android.R.attr.state_selected &&
                    it != android.R.attr.state_activated
        }.toIntArray()
    }
}
