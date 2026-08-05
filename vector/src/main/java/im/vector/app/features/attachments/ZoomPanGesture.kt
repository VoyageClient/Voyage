/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import kotlin.math.hypot
import kotlin.math.max

/**
 * Pinch-zoom and pan, shared by the attachment previewer and both editors.
 *
 * PhotoView and [android.view.ScaleGestureDetector] both stop reporting once the fingers converge
 * inside a ~27mm minimum span, which is the point you reach part way through zooming out; the raw
 * pointer span has no such cutoff. Callers with edits competing for the same gesture drive it
 * themselves; the rest hand everything to [onTouchEvent].
 */
class ZoomPanGesture(
        private val minZoom: Float,
        private val maxZoom: Float,
        private val springBackBelowFit: Boolean = false,
        private val onChanged: () -> Unit,
) {

    var zoom = 1f
        private set
    var panX = 0f
        private set
    var panY = 0f
        private set

    /** Size the content occupies at 1x, and the viewport it sits in. Both in pixels. */
    var contentWidth = 0f
    var contentHeight = 0f
    var viewportWidth = 0f
    var viewportHeight = 0f

    var isPinching = false
        private set

    /** Raised when the caller should hand the gesture to a parent pager, or take it back. */
    var onDisallowIntercept: ((Boolean) -> Unit)? = null

    /** Raised on a touch that neither zoomed nor panned. */
    var onTap: (() -> Unit)? = null

    private var lastSpan = 0f
    private var panning = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var moved = false
    private var springBackAnimator: ValueAnimator? = null

    fun reset() {
        springBackAnimator?.cancel()
        springBackAnimator = null
        zoom = 1f
        panX = 0f
        panY = 0f
        onChanged()
    }

    fun beginPinch(event: MotionEvent) {
        isPinching = true
        panning = false
        lastSpan = max(spanOf(event), MIN_PINCH_SPAN_PX)
    }

    fun applyPinch(event: MotionEvent) {
        // Floored rather than dropping the frame, which would leave a stale lastSpan to divide by.
        val span = max(spanOf(event), MIN_PINCH_SPAN_PX)
        val previousZoom = zoom
        // A per-frame safety net; a legitimate pinch never doubles the span between touch events.
        val ratio = (span / lastSpan).coerceIn(1f / MAX_PINCH_RATIO_PER_FRAME, MAX_PINCH_RATIO_PER_FRAME)
        zoom = (zoom * ratio).coerceIn(minZoom, maxZoom)
        lastSpan = span

        val factor = zoom / previousZoom
        val focusX = (event.getX(0) + event.getX(1)) / 2f
        val focusY = (event.getY(0) + event.getY(1)) / 2f
        // Scale about the focus point only: following it as well turns a pinch into a pan, since
        // holding one finger still drags the midpoint toward it as the other moves.
        val centreX = viewportWidth / 2f + panX
        val centreY = viewportHeight / 2f + panY
        panX = focusX - factor * (focusX - centreX) - viewportWidth / 2f
        panY = focusY - factor * (focusY - centreY) - viewportHeight / 2f
        clampPan()
        onChanged()
    }

    fun panBy(dx: Float, dy: Float) {
        panX += dx
        panY += dy
        clampPan()
        onChanged()
    }

    /** Once the content is no larger than the viewport it is forced back to centre. */
    fun clampPan() {
        if (zoom <= 1f) {
            panX = 0f
            panY = 0f
            return
        }
        val maxX = max(0f, (contentWidth * zoom - viewportWidth) / 2f)
        val maxY = max(0f, (contentHeight * zoom - viewportHeight) / 2f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    /** Rubber-band: pinching below fit is allowed, then springs back like the media viewer does. */
    private fun animateSpringBack() {
        val from = zoom
        springBackAnimator?.cancel()
        springBackAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SPRING_BACK_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                zoom = from + (1f - from) * fraction
                clampPan()
                onChanged()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    springBackAnimator = null
                }
            })
            start()
        }
    }

    /** The whole state machine, for callers with nothing else competing for the gesture. */
    @Suppress("ReturnCount")
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                panning = zoom > 1f
                moved = false
                // The DOWN must be consumed or no second finger ever arrives and a pinch can never
                // start. A pager can still take over later, as long as we don't disallow it.
                if (panning) onDisallowIntercept?.invoke(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    beginPinch(event)
                    onDisallowIntercept?.invoke(true)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPinching && event.pointerCount >= 2) {
                    applyPinch(event)
                    moved = true
                } else if (panning) {
                    panBy(event.x - lastTouchX, event.y - lastTouchY)
                    lastTouchX = event.x
                    lastTouchY = event.y
                    moved = true
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Re-baseline on the remaining pointer so lifting a finger doesn't jump the view.
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastTouchX = event.getX(remaining)
                lastTouchY = event.getY(remaining)
                if (event.pointerCount - 1 < 2) {
                    isPinching = false
                    panning = zoom > 1f
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPinching = false
                panning = false
                if (zoom <= 1f) onDisallowIntercept?.invoke(false)
                if (springBackBelowFit && zoom < 1f) animateSpringBack()
                if (!moved && event.actionMasked == MotionEvent.ACTION_UP) onTap?.invoke()
                return true
            }
        }
        return false
    }

    private fun spanOf(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }

    companion object {
        /** Below this the span is dominated by touch noise, and crossed fingers send zoom haywire. */
        private const val MIN_PINCH_SPAN_PX = 32f
        private const val MAX_PINCH_RATIO_PER_FRAME = 2f
        private const val SPRING_BACK_MS = 220L
    }
}
