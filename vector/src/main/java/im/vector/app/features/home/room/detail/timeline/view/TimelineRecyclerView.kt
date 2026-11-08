/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.view

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

class TimelineRecyclerView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    /**
     * While set, rows never receive touches (no press ripple, clicks or long-presses) and the list
     * scrolls itself. Item touch listeners still see every event: RecyclerView offers each MOVE and UP
     * to them from onTouchEvent until one intercepts.
     */
    var blocksChildTouches = false

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        val intercepted = super.onInterceptTouchEvent(e)
        return intercepted || blocksChildTouches
    }
}
