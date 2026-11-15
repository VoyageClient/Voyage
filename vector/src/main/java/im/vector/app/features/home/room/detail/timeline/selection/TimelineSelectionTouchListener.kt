/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.selection

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.prefetchForScroll
import kotlin.math.abs

/**
 * While selecting, taps toggle rows and held vertical drags select ranges, including the gutter.
 * Rows don't see these touches: [im.vector.app.features.home.room.detail.timeline.view.TimelineRecyclerView.blocksChildTouches].
 */
class TimelineSelectionTouchListener(
        private val recyclerView: RecyclerView,
        private val callback: Callback,
) : RecyclerView.OnItemTouchListener {

    interface Callback {
        fun isSelectionActive(): Boolean
        fun selectableEventIdAt(adapterPosition: Int): String?

        /** Where [eventId] currently is in the adapter, which rows arriving above it move. */
        fun adapterPositionOf(eventId: String): Int?
        fun onTap(eventId: String)

        fun onAnchor(anchorEventId: String)
        fun onDragRange(eventIds: List<String>)

        /** The gesture that called [onAnchor] is over. */
        fun onAnchorReleased()
    }

    private val density = recyclerView.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(recyclerView.context).scaledTouchSlop
    private val edge = 64 * density
    private val maxAutoScrollSpeed = 1400 * density

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var moved = false
    private var stoppedScroll = false

    private var anchorEventId: String? = null
    private var dragging = false
    private var lastRange: Pair<Int, Int>? = null

    private val longPress = Runnable {
        if (moved || !callback.isSelectionActive()) return@Runnable
        val position = positionUnder(downY) ?: return@Runnable
        val eventId = callback.selectableEventIdAt(position) ?: return@Runnable
        anchor(eventId)
    }

    /**
     * Moves the list itself each frame by however far the elapsed time says. RecyclerView's own smooth
     * scroll can't be used: it cancels it on every touch event it hands to this listener, so it only ever
     * ran between finger movements. Prefetching, which scrollBy skips, is asked for separately.
     */
    private val autoScroll = object : Runnable {
        private var lastFrame = 0L
        private var remainder = 0f

        override fun run() {
            if (!dragging) return
            if (!callback.isSelectionActive()) {
                reset()
                return
            }
            val now = SystemClock.uptimeMillis()
            val elapsed = if (lastFrame == 0L) 16L else (now - lastFrame).coerceAtMost(50L)
            lastFrame = now
            remainder += autoScrollSpeedFor(lastY) * elapsed / 1000f
            val step = remainder.toInt()
            if (step != 0) {
                remainder -= step
                recyclerView.scrollBy(0, step)
                recyclerView.prefetchForScroll(0, step)
                updateRange(lastY)
            }
            ViewCompat.postOnAnimation(recyclerView, this)
        }

        fun stop() {
            recyclerView.removeCallbacks(this)
            lastFrame = 0L
            remainder = 0f
        }
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        if (!callback.isSelectionActive()) {
            reset()
            return false
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                downX = e.x
                downY = e.y
                lastY = e.y
                // A touch that stops the list moving is only a stop: neither a tap nor the start of a hold.
                stoppedScroll = rv.scrollState != RecyclerView.SCROLL_STATE_IDLE
                if (!stoppedScroll && callback.isSelectionActive()) {
                    recyclerView.postDelayed(longPress, SELECTING_HOLD_MS)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (abs(e.x - downX) > touchSlop || abs(e.y - downY) > touchSlop)) {
                    moved = true
                    recyclerView.removeCallbacks(longPress)
                }
                if (anchorEventId != null && abs(e.y - downY) > touchSlop) {
                    startDrag(e.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                // A held press already selected its row.
                val tap = !moved && !stoppedScroll && anchorEventId == null && callback.isSelectionActive()
                if (tap) positionUnder(e.y)?.let { callback.selectableEventIdAt(it) }?.let { callback.onTap(it) }
                reset()
                // Claiming the UP keeps the list from treating it as the end of its own gesture.
                return tap
            }
            MotionEvent.ACTION_CANCEL -> reset()
            MotionEvent.ACTION_POINTER_DOWN -> {
                moved = true
                recyclerView.removeCallbacks(longPress)
            }
        }
        return false
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        if (!callback.isSelectionActive()) {
            reset()
            return
        }
        if (!dragging) return
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                lastY = e.y
                updateRange(e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit

    /** Another listener (a swipe) claimed the gesture, so this one never sees its end. */
    fun cancelGesture() = reset()

    private fun anchor(eventId: String) {
        anchorEventId = eventId
        callback.onAnchor(eventId)
    }

    private fun startDrag(y: Float) {
        if (anchorEventId == null) return
        dragging = true
        lastY = y
        recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
        updateRange(y)
        ViewCompat.postOnAnimation(recyclerView, autoScroll)
    }

    private fun reset() {
        recyclerView.removeCallbacks(longPress)
        autoScroll.stop()
        val wasAnchored = anchorEventId != null
        moved = false
        dragging = false
        anchorEventId = null
        lastRange = null
        if (wasAnchored) callback.onAnchorReleased()
    }

    private fun updateRange(y: Float) {
        val anchorId = anchorEventId ?: return
        val anchorPosition = callback.adapterPositionOf(anchorId) ?: return
        val position = positionUnder(y.coerceIn(0f, (recyclerView.height - 1).toFloat())) ?: return
        val range = minOf(anchorPosition, position) to maxOf(anchorPosition, position)
        if (range == lastRange) return
        lastRange = range
        callback.onDragRange((range.first..range.second).mapNotNull { callback.selectableEventIdAt(it) })
    }

    // By the decorated bounds, so the selection gutter beside a row counts as the row.
    private fun positionUnder(y: Float): Int? {
        val layoutManager = recyclerView.layoutManager ?: return null
        for (i in 0 until recyclerView.childCount) {
            val child: View = recyclerView.getChildAt(i)
            val top = layoutManager.getDecoratedTop(child) + child.translationY
            val bottom = layoutManager.getDecoratedBottom(child) + child.translationY
            if (y >= top && y < bottom) {
                return recyclerView.getChildAdapterPosition(child).takeIf { it != RecyclerView.NO_POSITION }
            }
        }
        return null
    }

    /** Pixels per second; negative scrolls up. Ramps with how deep into the edge band the finger is. */
    private fun autoScrollSpeedFor(y: Float): Float {
        val depth = when {
            y < edge -> -(1 - y / edge)
            y > recyclerView.height - edge -> 1 - (recyclerView.height - y) / edge
            else -> return 0f
        }.coerceIn(-1f, 1f)
        return maxAutoScrollSpeed * depth * abs(depth)
    }

    companion object {
        private const val SELECTING_HOLD_MS = 300L
    }
}
