/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.HorizontalScrollView

/**
 * A [HorizontalScrollView] that does not flash its scrollbar on the initial layout, but shows it
 * normally (fading) once the user starts interacting with it.
 */
class NoInitialScrollbarHorizontalScrollView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    private var userInteracted = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // onInterceptTouchEvent always receives the initial ACTION_DOWN, whereas onTouchEvent may
        // only start at ACTION_MOVE once a drag is intercepted.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            userInteracted = true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            userInteracted = true
        }
        return super.onTouchEvent(ev)
    }

    override fun awakenScrollBars(): Boolean = userInteracted && super.awakenScrollBars()

    override fun awakenScrollBars(startDelay: Int): Boolean = userInteracted && super.awakenScrollBars(startDelay)

    override fun awakenScrollBars(startDelay: Int, invalidate: Boolean): Boolean =
            userInteracted && super.awakenScrollBars(startDelay, invalidate)
}
