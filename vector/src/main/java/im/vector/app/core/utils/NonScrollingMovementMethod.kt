/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.method.MovementMethod
import android.view.MotionEvent
import android.widget.TextView

/**
 * Wraps a movement method to drop the drag-scrolling LinkMovementMethod inherits from
 * ScrollingMovementMethod, which lets a height-capped preview scroll its clipped text away.
 */
class NonScrollingMovementMethod(private val delegate: MovementMethod) : MovementMethod by delegate {

    override fun onTouchEvent(widget: TextView, text: Spannable, event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_MOVE) return false
        return delegate.onTouchEvent(widget, text, event)
    }
}

fun MovementMethod.nonScrolling() = NonScrollingMovementMethod(this)

val nonScrollingLinkMovementMethod = NonScrollingMovementMethod(LinkMovementMethod.getInstance())
