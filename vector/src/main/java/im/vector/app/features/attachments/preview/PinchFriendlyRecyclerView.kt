/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

/**
 * A pager that never turns a pinch into a page swipe. The zoomable page reopens the pager's
 * intercept as soon as it passes 1x, which otherwise steals the gesture halfway through a
 * pinch-out and leaves the zoom stuck.
 */
class PinchFriendlyRecyclerView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.pointerCount > 1) return false
        return super.onInterceptTouchEvent(e)
    }
}
