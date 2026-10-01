/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.core.view.ViewCompat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A sideways-dragged, flingable degree ruler for a full turn, read against a fixed center marker.
 * It wraps round: past 359° comes 0° again.
 */
class RotationDialView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Raised with the new angle in [0, 360); [moving] is false once a drag or fling has settled. */
    var onChanged: ((degrees: Float, moving: Boolean) -> Unit)? = null

    var snapEnabled = false

    /** Unwrapped, so a fling across 0° keeps going rather than jumping. */
    private var position = 0f

    /** Set from outside (a twist, an undo) without raising [onChanged]. */
    var value: Float
        get() = AngleSnap.normalise(position)
        set(v) {
            if (dragging || !scroller.isFinished) return
            position = AngleSnap.normalise(v)
            reported = position
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val pixelsPerDegree = 3f * density
    private val minorTickHeight = 8f * density
    private val majorTickHeight = 16f * density

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        strokeWidth = 1f * density
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 1.5f * density
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val majorLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB0FFFFFF.toInt()
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
    }

    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var lastX = 0f
    private var dragging = false
    private var reported = 0f

    /** Lifts the ticks off the bottom edge, level with the middle of the ±90° buttons beside them. */
    private val tickLift = 12f * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (56f * density + tickLift).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
                getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
                resolveSize(desired, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val baseline = height - paddingBottom - tickLift
        val shown = shownPosition()
        val visibleDegrees = centerX / pixelsPerDegree
        val first = ceil((shown - visibleDegrees) / TICK_STEP).toInt() * TICK_STEP
        val last = floor((shown + visibleDegrees) / TICK_STEP).toInt() * TICK_STEP
        var degree = first
        while (degree <= last) {
            val x = centerX + (degree - shown) * pixelsPerDegree
            val major = degree.mod(MAJOR_STEP) == 0
            // Fade toward the edges, so the ruler reads as turning away rather than cut off.
            val fade = 1f - (abs(x - centerX) / centerX).coerceIn(0f, 1f)
            val paint = if (major) majorTickPaint else tickPaint
            paint.alpha = ((if (major) 0xFF else 0x99) * fade).toInt()
            canvas.drawLine(x, baseline - if (major) majorTickHeight else minorTickHeight, x, baseline, paint)
            if (major && abs(x - centerX) > 24f * density) {
                majorLabelPaint.alpha = (0xB0 * fade).toInt()
                canvas.drawText("${degree.mod(360)}°", x, baseline - majorTickHeight - 4f * density, majorLabelPaint)
            }
            degree += TICK_STEP
        }
        canvas.drawLine(centerX, baseline - majorTickHeight - 4f * density, centerX, baseline, markerPaint)
        canvas.drawText("${reported.roundToInt() % 360}°", centerX, baseline - majorTickHeight - 10f * density, labelPaint)
    }

    /**
     * Where the ruler is drawn: under the finger, except while the angle is caught on a snap point,
     * where it holds still so the catch is visible and not just felt.
     */
    private fun shownPosition(): Float {
        if (!snapEnabled || !AngleSnap.isSnapped(reported)) return position
        var offset = reported - AngleSnap.normalise(position)
        if (offset > 180f) offset -= 360f
        if (offset < -180f) offset += 360f
        return position + offset
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scroller.forceFinished(true)
                lastX = event.x
                dragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                // Dragging the ruler left brings larger values under the marker.
                moveTo(position - (event.x - lastX) / pixelsPerDegree)
                lastX = event.x
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return true
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                tracker.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                val velocity = tracker.xVelocity
                tracker.recycle()
                velocityTracker = null
                if (event.actionMasked == MotionEvent.ACTION_UP && abs(velocity) > minFlingVelocity) {
                    // Flung in pixels, so the ruler decelerates like any other scrolling list.
                    val start = (position * pixelsPerDegree).roundToInt()
                    scroller.fling(start, 0, -velocity.roundToInt(), 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2, 0, 0)
                    ViewCompat.postInvalidateOnAnimation(this)
                } else {
                    settle()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        moveTo(scroller.currX / pixelsPerDegree)
        if (scroller.isFinished) settle() else ViewCompat.postInvalidateOnAnimation(this)
    }

    /** Whole degrees: finer than that is not something a finger on a ruler can pick. */
    private fun moveTo(raw: Float) {
        position = raw
        val next = AngleSnap.snap(raw.roundToInt().toFloat(), snapEnabled)
        if (next != reported) {
            if (snapEnabled && AngleSnap.isSnapped(next) && !AngleSnap.isSnapped(reported)) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            reported = next
            onChanged?.invoke(next, true)
        }
        invalidate()
    }

    private fun settle() {
        position = reported
        invalidate()
        onChanged?.invoke(reported, false)
    }

    companion object {
        private const val TICK_STEP = 5
        private const val MAJOR_STEP = 45
    }
}
