/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.platform

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.appbar.AppBarLayout

/**
 * AppBarLayout behavior that only lets the header scroll away when the scrolling child actually has more
 * content than fits on screen. Without this, CoordinatorLayout collapses the header on any nested-scroll
 * gesture, letting a short list be dragged past its last entry.
 */
class ScrollAwareAppBarBehavior(context: Context, attrs: AttributeSet?) : AppBarLayout.Behavior(context, attrs) {

    override fun onStartNestedScroll(
            parent: CoordinatorLayout,
            child: AppBarLayout,
            directTargetChild: View,
            target: View,
            nestedScrollAxes: Int,
            type: Int
    ): Boolean {
        // Allow scrolling when the list overflows, or whenever the header is already collapsed so it can
        // always be pulled back into view.
        val headerCollapsed = topAndBottomOffset != 0
        return (canChildScroll(target) || headerCollapsed) &&
                super.onStartNestedScroll(parent, child, directTargetChild, target, nestedScrollAxes, type)
    }

    private fun canChildScroll(target: View): Boolean {
        return target.canScrollVertically(1) || target.canScrollVertically(-1)
    }
}
