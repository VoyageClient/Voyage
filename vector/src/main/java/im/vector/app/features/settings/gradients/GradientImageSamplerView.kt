/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.gradients

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.get
import im.vector.app.features.attachments.ZoomPanGesture
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An image to take colors from, the way the image editor's eyedropper does: each color is a point on the
 * image. A tap places one, dragging a point moves it, and two fingers zoom and pan.
 */
class GradientImageSamplerView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
) : View(context, attrs) {

    var maxPoints = CustomGradient.MAX_COLORS

    /** The points' colors in order, and which one is selected; raised while dragging too. */
    var onPointsChanged: ((colors: List<Int>, selected: Int) -> Unit)? = null

    private var bitmap: Bitmap? = null

    // Fractions of the image's width and height.
    private val points = mutableListOf<PointF>()
    var selectedIndex = -1
        private set
    var colors: List<Int> = emptyList()
        private set

    // A touch only becomes a point (or moves one) once it is a tap or a one-finger drag, so the first
    // finger of a pinch never leaves a point behind.
    private var touchActive = false
    private var dragging = false
    private var grabbedIndex: Int? = null
    private var grabOffsetX = 0f
    private var grabOffsetY = 0f
    private var downX = 0f
    private var downY = 0f
    private var touchX = 0f
    private var touchY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val imageRect = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val loupeRadius = dp(28f)
    private val loupeOffset = dp(72f)
    private val markerRadius = dp(8f)
    private val selectedMarkerRadius = dp(12f)
    private val grabRadius = dp(28f)

    private val gesture = ZoomPanGesture(MIN_ZOOM, MAX_ZOOM, panSlackFraction = PAN_SLACK_FRACTION, panWithPinch = true) { invalidate() }.apply {
        onDisallowIntercept = { parent?.requestDisallowInterceptTouchEvent(it) }
    }

    fun setBitmap(value: Bitmap) {
        if (bitmap === value) return
        bitmap = value
        gesture.reset()
        notifyPoints()
        invalidate()
    }

    fun removeSelected() {
        if (selectedIndex !in points.indices) return
        points.removeAt(selectedIndex)
        selectedIndex = selectedIndex.coerceAtMost(points.lastIndex)
        notifyPoints()
        invalidate()
    }

    /** Reorders the colors; the selection stays on the same point. */
    fun movePoint(from: Int, to: Int) {
        if (from !in points.indices || to !in points.indices || from == to) return
        val selected = points.getOrNull(selectedIndex)
        points.add(to, points.removeAt(from))
        selectedIndex = points.indexOfFirst { it === selected }
        notifyPoints()
        invalidate()
    }

    fun select(index: Int) {
        if (index !in points.indices) return
        selectedIndex = index
        notifyPoints()
        invalidate()
    }

    /** The points as x, y pairs; for keeping them across a configuration change. */
    fun pointsState() = points.flatMap { listOf(it.x, it.y) }.toFloatArray()

    fun restorePoints(values: FloatArray, selected: Int) {
        if (values.size % 2 != 0) return
        points.clear()
        for (i in values.indices step 2) points += PointF(values[i], values[i + 1])
        selectedIndex = if (points.isEmpty()) -1 else selected.coerceIn(0, points.lastIndex)
        notifyPoints()
        invalidate()
    }

    private fun notifyPoints() {
        colors = if (bitmap == null) emptyList() else points.map { sample(it.x, it.y) }
        onPointsChanged?.invoke(colors, selectedIndex)
    }

    private fun sample(nx: Float, ny: Float): Int {
        val image = bitmap ?: return Color.BLACK
        val x = (nx * (image.width - 1)).roundToInt()
        val y = (ny * (image.height - 1)).roundToInt()
        return image[x, y] or Color.BLACK
    }

    private fun computeGeometry(): Boolean {
        val image = bitmap ?: return false
        if (width <= 0 || height <= 0) return false
        val fit = min(width.toFloat() / image.width, height.toFloat() / image.height)
        gesture.contentWidth = image.width * fit
        gesture.contentHeight = image.height * fit
        gesture.viewportWidth = width.toFloat()
        gesture.viewportHeight = height.toFloat()
        val scale = fit * gesture.zoom
        val cx = width / 2f + gesture.panX
        val cy = height / 2f + gesture.panY
        imageRect.set(cx - image.width * scale / 2f, cy - image.height * scale / 2f, cx + image.width * scale / 2f, cy + image.height * scale / 2f)
        return true
    }

    private fun screenX(nx: Float) = imageRect.left + nx * imageRect.width()
    private fun screenY(ny: Float) = imageRect.top + ny * imageRect.height()
    private fun imageX(x: Float) = ((x - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
    private fun imageY(y: Float) = ((y - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        if (!computeGeometry()) return
        canvas.drawBitmap(image, null, imageRect, bitmapPaint)
        points.forEachIndexed { index, point ->
            if (index != selectedIndex) drawMarker(canvas, point, colors.getOrNull(index), markerRadius)
        }
        points.getOrNull(selectedIndex)?.let { drawMarker(canvas, it, colors.getOrNull(selectedIndex), selectedMarkerRadius) }
        if (dragging) colors.getOrNull(selectedIndex)?.let { drawLoupe(canvas, it) }
    }

    private fun drawMarker(canvas: Canvas, point: PointF, color: Int?, radius: Float) {
        val cx = screenX(point.x)
        val cy = screenY(point.y)
        if (color != null) {
            fillPaint.color = color
            canvas.drawCircle(cx, cy, radius, fillPaint)
        }
        drawContrastRing(canvas, cx, cy, radius, dp(2f))
    }

    /** Held clear of the finger, above it unless that would leave the view. */
    private fun drawLoupe(canvas: Canvas, color: Int) {
        val above = touchY - loupeOffset
        val cy = if (above - loupeRadius >= 0f) above else touchY + loupeOffset
        val cx = touchX.coerceIn(loupeRadius, (width - loupeRadius).coerceAtLeast(loupeRadius))
        fillPaint.color = color
        canvas.drawCircle(cx, cy, loupeRadius, fillPaint)
        drawContrastRing(canvas, cx, cy, loupeRadius, dp(3f))
    }

    /** White inside a thin dark edge, so it reads on light and dark images alike. */
    private fun drawContrastRing(canvas: Canvas, cx: Float, cy: Float, radius: Float, stroke: Float) {
        ringPaint.color = OUTLINE
        ringPaint.strokeWidth = stroke + dp(2f)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        ringPaint.color = Color.WHITE
        ringPaint.strokeWidth = stroke
        canvas.drawCircle(cx, cy, radius, ringPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null || imageRect.isEmpty) return false
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount >= 2) {
            touchActive = false
            dragging = false
            gesture.beginPinch(event)
            parent?.requestDisallowInterceptTouchEvent(true)
            invalidate()
            return true
        }
        if (gesture.isPinching) return gesture.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touchActive = true
                dragging = false
                downX = event.x
                downY = event.y
                // Grabbing a marker keeps it under the finger where it was held.
                grabbedIndex = nearestPoint(event.x, event.y)?.also { grabbed ->
                    grabOffsetX = screenX(points[grabbed].x) - event.x
                    grabOffsetY = screenY(points[grabbed].y) - event.y
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchActive) return true
                if (!dragging && hypot(event.x - downX, event.y - downY) > touchSlop) startDrag()
                if (dragging) moveTo(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (touchActive && !dragging) {
                    // A tap selects the marker under it, or places a new point there.
                    val grabbed = grabbedIndex
                    if (grabbed != null) select(grabbed) else if (startDrag()) moveTo(event.x, event.y)
                    performClick()
                }
                endTouch()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                endTouch()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Picks up the grabbed marker, else a new point at the finger; false when there is no room for one. */
    private fun startDrag(): Boolean {
        val grabbed = grabbedIndex
        if (grabbed != null) {
            selectedIndex = grabbed
        } else if (maxPoints == 1 && points.size == 1) {
            // A lone eyedropper point follows the finger wherever it lands.
            grabOffsetX = 0f
            grabOffsetY = 0f
            selectedIndex = 0
        } else {
            if (points.size >= maxPoints) {
                touchActive = false
                return false
            }
            grabOffsetX = 0f
            grabOffsetY = 0f
            points += PointF(imageX(downX), imageY(downY))
            selectedIndex = points.lastIndex
        }
        dragging = true
        return true
    }

    private fun endTouch() {
        touchActive = false
        dragging = false
        grabbedIndex = null
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    override fun performClick(): Boolean = super.performClick()

    private fun nearestPoint(x: Float, y: Float): Int? {
        val (index, distance) = points.indices
                .map { it to hypot(x - screenX(points[it].x), y - screenY(points[it].y)) }
                .minByOrNull { it.second } ?: return null
        return index.takeIf { distance <= grabRadius }
    }

    private fun moveTo(x: Float, y: Float) {
        touchX = x
        touchY = y
        points.getOrNull(selectedIndex)?.set(imageX(x + grabOffsetX), imageY(y + grabOffsetY))
        notifyPoints()
        invalidate()
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    companion object {
        private const val MIN_ZOOM = 1f
        private const val MAX_ZOOM = 20f
        private const val PAN_SLACK_FRACTION = 0.25f
        private const val OUTLINE = 0x99000000.toInt()
    }
}
