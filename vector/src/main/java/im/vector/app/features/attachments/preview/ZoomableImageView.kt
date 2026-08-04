/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Rubber-band floor only; releasing below fit springs back to 1x. */
private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 20f
private const val SPRING_BACK_MS = 220L

/**
 * Pinch spans below this are dominated by touch noise, and fingers that meet or cross would
 * otherwise send the zoom haywire. Note this is a *floor*, not a cutoff: the span is clamped rather
 * than the frame dropped, so zooming out never stalls.
 */
private const val MIN_PINCH_SPAN_PX = 32f
private const val MAX_PINCH_RATIO_PER_FRAME = 2f

/**
 * Zoomable image for the attachment pager.
 *
 * PhotoView is used elsewhere in the app, but it drives its zoom from [android.view.ScaleGestureDetector],
 * which stops reporting once the fingers converge inside its ~27mm minimum span. Its drag detector
 * keeps running, so a pinch-out silently turns into a pan partway through. Tracking the raw pointer
 * span has no such cutoff.
 */
class ZoomableImageView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    /**
     * Also re-asserts the scale type: the shared bind path switches to FIT_CENTER for non-media
     * attachments, and a recycled view would otherwise keep it and ignore the zoom matrix forever.
     */
    var zoomEnabled: Boolean = true
        set(value) {
            field = value
            scaleType = if (value) ScaleType.MATRIX else ScaleType.FIT_CENTER
            if (value) updateBaseMatrix() else resetZoom()
        }

    private val baseMatrix = Matrix()
    private val drawMatrix = Matrix()

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var contentWidth = 0f
    private var contentHeight = 0f

    private var lastSpan = 0f
    private var pinching = false
    private var panning = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var movedDuringGesture = false
    private var springBackAnimator: ValueAnimator? = null

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        zoom = 1f
        panX = 0f
        panY = 0f
        updateBaseMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateBaseMatrix()
    }

    fun resetZoom() {
        springBackAnimator?.cancel()
        springBackAnimator = null
        zoom = 1f
        panX = 0f
        panY = 0f
        applyMatrix()
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
                applyMatrix()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    springBackAnimator = null
                }
            })
            start()
        }
    }

    private fun updateBaseMatrix() {
        val current = drawable ?: return
        val intrinsicWidth = current.intrinsicWidth.toFloat()
        val intrinsicHeight = current.intrinsicHeight.toFloat()
        if (intrinsicWidth <= 0f || intrinsicHeight <= 0f || width == 0 || height == 0) return
        val scale = min(width / intrinsicWidth, height / intrinsicHeight)
        contentWidth = intrinsicWidth * scale
        contentHeight = intrinsicHeight * scale
        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate((width - contentWidth) / 2f, (height - contentHeight) / 2f)
        applyMatrix()
    }

    private fun applyMatrix() {
        drawMatrix.set(baseMatrix)
        drawMatrix.postScale(zoom, zoom, width / 2f, height / 2f)
        drawMatrix.postTranslate(panX, panY)
        imageMatrix = drawMatrix
        invalidate()
    }

    /** Once the image is no larger than the viewport it is forced back to centre. */
    private fun clampPan() {
        if (zoom <= 1f) {
            panX = 0f
            panY = 0f
            return
        }
        val maxX = max(0f, (contentWidth * zoom - width) / 2f)
        val maxY = max(0f, (contentHeight * zoom - height) / 2f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!zoomEnabled || drawable == null) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                panning = zoom > 1f
                movedDuringGesture = false
                // The DOWN must be consumed or the rest of the gesture is never delivered, so a
                // second finger would never arrive and a pinch could never start. The pager can
                // still take over later via onInterceptTouchEvent as long as we don't disallow it.
                if (panning) parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    pinching = true
                    panning = false
                    lastSpan = max(spanOf(event), MIN_PINCH_SPAN_PX)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinching && event.pointerCount >= 2) {
                    applyPinch(event)
                    movedDuringGesture = true
                } else if (panning) {
                    panX += event.x - lastTouchX
                    panY += event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    clampPan()
                    applyMatrix()
                    movedDuringGesture = true
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Re-baseline on the remaining pointer so lifting a finger doesn't jump the view,
                // then hand the gesture back to panning like the media viewer does.
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastTouchX = event.getX(remaining)
                lastTouchY = event.getY(remaining)
                if (event.pointerCount - 1 < 2) {
                    pinching = false
                    panning = zoom > 1f
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pinching = false
                panning = false
                if (zoom <= 1f) parent?.requestDisallowInterceptTouchEvent(false)
                if (zoom < 1f) animateSpringBack()
                if (!movedDuringGesture && event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun applyPinch(event: MotionEvent) {
        // Floor the span rather than dropping the frame: fingers can meet or cross, and the span is
        // an absolute distance, so it collapses toward zero and grows again. Dropping the frame
        // would leave a stale lastSpan for the next one to divide by.
        val span = max(spanOf(event), MIN_PINCH_SPAN_PX)
        val previousZoom = zoom
        val ratio = (span / lastSpan).coerceIn(1f / MAX_PINCH_RATIO_PER_FRAME, MAX_PINCH_RATIO_PER_FRAME)
        zoom = (zoom * ratio).coerceIn(MIN_ZOOM, MAX_ZOOM)
        lastSpan = span

        val factor = zoom / previousZoom
        val focusX = (event.getX(0) + event.getX(1)) / 2f
        val focusY = (event.getY(0) + event.getY(1)) / 2f
        // Scale about the focus point only. Following the focus as well would turn a pinch into a
        // pan, because holding one finger still drags the midpoint toward it as the other moves.
        val centreX = width / 2f + panX
        val centreY = height / 2f + panY
        panX = focusX - factor * (focusX - centreX) - width / 2f
        panY = focusY - factor * (focusY - centreY) - height / 2f
        clampPan()
        applyMatrix()
    }

    private fun spanOf(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }
}
