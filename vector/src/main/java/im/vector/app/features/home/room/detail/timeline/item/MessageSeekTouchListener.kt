/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

@SuppressLint("ClickableViewAccessibility")
internal class MessageSeekTouchListener(
        private val view: View,
        private val onSeekTouch: (MotionEvent) -> Unit,
) : View.OnTouchListener {

    private var longPressed = false
    private var dragging = false
    private val detector = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            dragging = true
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!view.isLongClickable) return
            longPressed = true
            // Cancel the scrub before opening actions so releasing the hold cannot commit a seek.
            val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
            onSeekTouch(cancel)
            cancel.recycle()
            view.performLongClick()
        }
    })

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            longPressed = false
            dragging = false
            view.parent?.requestDisallowInterceptTouchEvent(true)
        }
        detector.onTouchEvent(event)
        if (!longPressed && (event.actionMasked != MotionEvent.ACTION_MOVE || dragging)) onSeekTouch(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            view.parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    fun dispose() {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        onTouch(view, event)
        event.recycle()
        view.setOnTouchListener(null)
    }
}
