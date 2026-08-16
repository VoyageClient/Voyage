/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.render

import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import android.widget.TableLayout
import kotlin.math.abs

/**
 * A HorizontalScrollView that lets its TableLayout child shrink up to ~25% (down to ~75% of its
 * natural width) before falling back to horizontal scrolling.
 *
 * Touch handling: preemptively claims the gesture on ACTION_DOWN so the parent (swipe-to-reply
 * gesture detector / RecyclerView) can't steal an early horizontal MOVE. As soon as the first
 * MOVE past slop arrives we re-check: if the user is actually scrolling vertically, we release
 * the claim and let the parent take over for the rest of the gesture.
 */
class ShrinkableHorizontalScrollView(context: Context) : HorizontalScrollView(context) {

    // Shrinking relies on cells re-wrapping to taller rows. With single-line cells it would just
    // clip trailing characters, so callers disable it and we scroll instead.
    var allowShrink: Boolean = true

    private val shrinkFactor: Float = 0.25f
    private var lastAvailable = -1
    private var lastNatural = -1
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var directionDecided = false
    private var beyondContent = false

    private var allowAwakenScrollbar = false
    private val resetAllowAwaken = Runnable { allowAwakenScrollbar = false }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            scrollBarFadeDuration = SCROLLBAR_FADE_DURATION_MS
            scrollBarDefaultDelayBeforeFade = SCROLLBAR_DELAY_BEFORE_FADE_MS
        }
        // Prevents the edge glow / overscroll springback from re-awakening the scrollbar after release.
        overScrollMode = OVER_SCROLL_NEVER
    }

    override fun awakenScrollBars(startDelay: Int, invalidate: Boolean): Boolean {
        if (!allowAwakenScrollbar) return false
        return super.awakenScrollBars(startDelay, invalidate)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child: View? = if (childCount > 0) getChildAt(0) else null
        if (child !is TableLayout || MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val available = MeasureSpec.getSize(widthMeasureSpec)
        // The unbounded probe re-lays-out every cell's text to find the table's natural width, which is
        // fixed once the tree is built — so probe only on the first measure or a width change, not on
        // every relayout (selection-handle drags requestLayout the whole table per pixel). super.onMeasure
        // below still lays the child out.
        if (lastNatural < 0 || available != lastAvailable) {
            child.isShrinkAllColumns = false
            val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            child.measure(unbounded, heightMeasureSpec)
            lastNatural = child.measuredWidth
            lastAvailable = available
        }
        child.isShrinkAllColumns = allowShrink && lastNatural > available && lastNatural <= (available / (1f - shrinkFactor)).toInt()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (isBeyondContent(ev)) return false
        handleTouchPriority(ev)
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (isBeyondContent(ev)) return false
        handleTouchPriority(ev)
        return super.onTouchEvent(ev)
    }

    // This view is as wide as the message, the table inside it usually isn't. A press on the empty
    // strip past the table's right edge belongs to the message: consuming it here would swallow the
    // message's tap, long-press and ripple.
    private fun isBeyondContent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val child = if (childCount > 0) getChildAt(0) else null
            beyondContent = child == null || ev.x + scrollX > child.right
        }
        return beyondContent
    }

    private fun handleTouchPriority(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                directionDecided = false
                allowAwakenScrollbar = true
                removeCallbacks(resetAllowAwaken)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (!directionDecided) {
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                if (dx >= touchSlop || dy >= touchSlop) {
                    directionDecided = true
                    if (dy > dx) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Keep the scrollbar visible / fading naturally for a beat after release (covers any
                // residual fling), then stop letting non-user events re-awaken it.
                postDelayed(resetAllowAwaken, SCROLLBAR_USER_WINDOW_AFTER_RELEASE_MS)
            }
        }
    }

    // Tapping a selectable cell/code view focuses it, and stock requestChildFocus scrolls to reveal
    // the whole focused view — jumping the table on a mere tap. Keep the scroll position instead.
    override fun requestChildFocus(child: View?, focused: View?) {
        val savedX = scrollX
        super.requestChildFocus(child, focused)
        if (scrollX != savedX) scrollTo(savedX, scrollY)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(resetAllowAwaken)
        allowAwakenScrollbar = false
    }

    companion object {
        private const val SCROLLBAR_FADE_DURATION_MS = 250
        private const val SCROLLBAR_DELAY_BEFORE_FADE_MS = 250
        private const val SCROLLBAR_USER_WINDOW_AFTER_RELEASE_MS = 1500L
    }
}
