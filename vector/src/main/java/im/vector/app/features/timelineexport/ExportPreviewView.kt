/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import androidx.core.view.ViewCompat
import im.vector.app.features.attachments.ZoomPanGesture
import timber.log.Timber
import kotlin.math.max

class ExportPreviewView(context: Context) : View(context) {
    var document: TimelineExportRenderer.Document? = null
        set(value) {
            snapAnimation?.cancel()
            val previous = field
            val oldScale = fitScale() * gesture.zoom
            val top = if (previous != null) visibleRange().first else 0
            val anchor = previous?.rows?.firstOrNull { it.top + it.height > top }
            previous?.context?.onResourceChanged = null
            field = value
            value?.let { current ->
                current.context.onResourceChanged = {
                    if (document === current) {
                        val scale = fitScale() * gesture.zoom
                        val viewportTop = visibleRange().first
                        val anchorIndex = current.rows.indexOfFirst { it.top + it.height > viewportTop }
                        val offset = viewportTop - (current.rows.getOrNull(anchorIndex)?.top ?: viewportTop)
                        if (current.remeasureDirtyRows()) {
                            val newTop = (current.rows.getOrNull(anchorIndex)?.top ?: viewportTop) + offset
                            updateGeometry()
                            gesture.setTransform(scale / fitScale(), gesture.panX, (current.height / 2f - newTop - height / scale / 2f) * scale)
                        }
                        invalidate()
                    }
                }
            }
            updateGeometry()
            if (previous != null && value != null && width > 0 && height > 0) {
                val row = value.rows.firstOrNull { candidate ->
                    anchor?.events?.firstOrNull()?.id?.let { id -> candidate.events.firstOrNull()?.id == id }
                            ?: (anchor?.label != null && candidate.label == anchor.label)
                }
                val scale = oldScale * previous.logicalWidth / value.logicalWidth
                val logicalTop = row?.let { it.top + top - (anchor?.top ?: 0) } ?: top
                gesture.setTransform(scale / fitScale(), 0f, (value.height / 2f - logicalTop - height / scale / 2f) * scale)
            } else fitWidth()
            invalidate()
        }
    var renderer: TimelineExportRenderer? = null
    private fun sceneWidth() = width.toFloat().coerceAtLeast(1f)
    private fun centerX() = sceneWidth() / 2f
    private val scroller = OverScroller(context)
    private var lastFlingX = 0
    private var lastFlingY = 0
    var onGroupToggle: ((String) -> Unit)? = null
    var onViewportChanged: (() -> Unit)? = null
    var splitHeight = 0
    private val splitPaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f) }
    private val gesture = ZoomPanGesture(.15f, 20f) { invalidate(); onViewportChanged?.invoke() }
    private var tapX = 0f
    private var tapY = 0f
    private var doubleTapInProgress = false
    private var pinched = false
    private var zoomFocusY = 0f
    private var snapAnimation: ValueAnimator? = null
    private val drawClip = Rect()
    private val doubleTap = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            doubleTapInProgress = true
            val doc = document ?: return false
            val current = fitScale() * gesture.zoom
            val fit = sceneWidth() / doc.logicalWidth
            val target = if (current > fit * 1.1f) fit else fit * 2f
            val ratio = target / current
            val panX = e.x - ratio * (e.x - sceneWidth() / 2f - gesture.panX) - sceneWidth() / 2f
            val panY = e.y - ratio * (e.y - height / 2f - gesture.panY) - height / 2f
            gesture.setTransform(target / fitScale(), panX, panY)
            return true
        }
        override fun onFling(first: MotionEvent?, last: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val doc = document ?: return false
            if (gesture.isPinching || pinched || doubleTapInProgress) return false
            val scale = fitScale() * gesture.zoom
            val maxX = ((doc.logicalWidth * scale - sceneWidth()) / 2).coerceAtLeast(0f).toInt()
            val maxY = ((doc.height * scale - height) / 2).coerceAtLeast(0f).toInt()
            lastFlingX = gesture.panX.toInt()
            lastFlingY = gesture.panY.toInt()
            scroller.fling(lastFlingX, lastFlingY, velocityX.toInt(), velocityY.toInt(), -maxX, maxX, -maxY, maxY)
            ViewCompat.postInvalidateOnAnimation(this@ExportPreviewView)
            return true
        }
    })

    init {
        setBackgroundColor(Color.BLACK)
        // Detached children cannot use RenderNode outlines; path clipping needs software before API 18.
        if (Build.VERSION.SDK_INT < 18) setLayerType(LAYER_TYPE_SOFTWARE, null)
        gesture.onTap = tap@{
            if (doubleTapInProgress) return@tap
            val doc = document
            if (doc != null) {
                val scale = fitScale() * gesture.zoom
                val x = (tapX - centerX() - gesture.panX) / scale + doc.logicalWidth / 2f
                val y = (tapY - height / 2f - gesture.panY) / scale + doc.height / 2f
                if (x in 0f..doc.logicalWidth.toFloat()) doc.rows.firstOrNull { y >= it.top && y < it.top + it.height }
                        ?.groupId?.let { onGroupToggle?.invoke(it) }
            }
            performClick()
        }
    }

    fun fitWidth() {
        val doc = document ?: return
        if (width <= 0 || height <= 0) return
        updateGeometry()
        val target = sceneWidth() / doc.logicalWidth
        gesture.setTransform(target / fitScale(), 0f, ((doc.height * target - height) / 2).coerceAtLeast(0f))
        invalidate()
        onViewportChanged?.invoke()
    }

    val zoomPercent: Int get() {
        val doc = document ?: return 100
        return (fitScale() * gesture.zoom / (sceneWidth() / doc.logicalWidth) * 100).toInt().coerceAtLeast(1)
    }

    fun zoomBy(factor: Float) {
        val doc = document ?: return
        scroller.forceFinished(true)
        val current = fitScale() * gesture.zoom
        val widthScale = sceneWidth() / doc.logicalWidth
        val target = (current * factor).coerceIn(fitScale() * .15f, widthScale * 20f)
        val ratio = target / current
        gesture.setTransform(target / fitScale(), gesture.panX * ratio, gesture.panY * ratio)
    }
    private fun fitScale(): Float = document?.let { minOf(sceneWidth() / it.logicalWidth, height.toFloat() / it.height).coerceAtLeast(.000001f) } ?: 1f
    private fun updateGeometry() {
        val doc = document ?: return
        gesture.contentWidth = doc.logicalWidth * fitScale()
        gesture.contentHeight = doc.height * fitScale()
        gesture.viewportWidth = sceneWidth()
        gesture.viewportHeight = height.toFloat()
        gesture.setZoomLimits(.15f, sceneWidth() / doc.logicalWidth / fitScale() * 20f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        snapAnimation?.cancel()
        val doc = document
        if (doc == null || oldw <= 0 || oldh <= 0) { updateGeometry(); fitWidth(); return }
        val oldFit = minOf(oldw.toFloat() / doc.logicalWidth, oldh.toFloat() / doc.height).coerceAtLeast(.000001f)
        val scale = oldFit * gesture.zoom
        val top = doc.height / 2f - gesture.panY / scale - oldh / scale / 2f
        updateGeometry()
        gesture.setTransform(scale / fitScale(), gesture.panX, (doc.height / 2f - top - h / scale / 2f) * scale)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val doc = document ?: return
        val scale = fitScale() * gesture.zoom
        val save = canvas.save()
        canvas.translate(centerX() + gesture.panX, height / 2f + gesture.panY)
        canvas.scale(scale, scale)
        canvas.translate(-doc.logicalWidth / 2f, -doc.height / 2f)
        canvas.clipRect(0, 0, doc.logicalWidth, doc.height)
        val clip = drawClip
        canvas.getClipBounds(clip)
        val top = max(0, clip.top)
        val bottom = minOf(doc.height, clip.bottom)
        val inner = canvas.save()
        canvas.translate(0f, top.toFloat())
        renderer?.draw(doc, canvas, top, doc.logicalWidth, (bottom - top).coerceAtLeast(1))
        canvas.restoreToCount(inner)
        if (splitHeight > 0) {
            val outputScale = doc.snapshot.options.width.toFloat() / doc.logicalWidth
            val boundaries = doc.boundaries(doc.snapshot.options.width)
            ExportSplitPlanner.split(boundaries, (doc.height * outputScale).toInt(), splitHeight).drop(1).forEach { part ->
                val y = part.top / outputScale
                if (y >= top && y <= bottom) canvas.drawLine(0f, y, doc.logicalWidth.toFloat(), y, splitPaint)
            }
        }
        canvas.restoreToCount(save)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            gesture.panBy((scroller.currX - lastFlingX).toFloat(), (scroller.currY - lastFlingY).toFloat())
            lastFlingX = scroller.currX
            lastFlingY = scroller.currY
            ViewCompat.postInvalidateOnAnimation(this)
        }
    }

    fun visibleRange(): Pair<Int, Int> {
        val doc = document ?: return 0 to 0
        val scale = fitScale() * gesture.zoom
        val center = doc.height / 2f - gesture.panY / scale
        return (center - height / scale / 2).toInt().coerceAtLeast(0) to (center + height / scale / 2).toInt().coerceAtMost(doc.height)
    }

    private fun snapZoom() {
        val doc = document ?: return
        val current = fitScale() * gesture.zoom
        val stops = listOf(fitScale(), sceneWidth() / doc.logicalWidth)
        val target = stops.minByOrNull { kotlin.math.abs(it / current - 1f) } ?: return
        if (kotlin.math.abs(target / current - 1f) > .055f || kotlin.math.abs(target - current) < .000001f) return
        val fromZoom = gesture.zoom
        val fromX = gesture.panX
        val fromY = gesture.panY
        val ratio = target / current
        val toX = 0f
        val toY = if (target == fitScale()) 0f else zoomFocusY - ratio * (zoomFocusY - height / 2f - fromY) - height / 2f
        snapAnimation?.cancel()
        snapAnimation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 140
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                gesture.setTransform(fromZoom + (target / fitScale() - fromZoom) * fraction,
                        fromX + (toX - fromX) * fraction, fromY + (toY - fromY) * fraction)
            }
            start()
        }
        Timber.d("ExportTrace canvas snapping from=%f to=%f fit=%f widthFit=%f", current, target, fitScale(), sceneWidth() / doc.logicalWidth)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        tapX = event.x; tapY = event.y
        val adjusted = MotionEvent.obtain(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            snapAnimation?.cancel()
            doubleTapInProgress = false
            pinched = false
        }
        if (event.pointerCount >= 2) {
            pinched = true
            zoomFocusY = (event.getY(0) + event.getY(1)) / 2f
        }
        doubleTap.onTouchEvent(adjusted)
        gesture.onTouchEvent(adjusted)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            doubleTapInProgress = false
            if (event.actionMasked == MotionEvent.ACTION_UP && pinched) snapZoom()
            Timber.d("ExportTrace canvas gesture ended zoom=%d panX=%f panY=%f pinched=%s", zoomPercent, gesture.panX, gesture.panY, pinched)
        }
        adjusted.recycle()
        return true
    }

    override fun onDetachedFromWindow() { snapAnimation?.cancel(); scroller.forceFinished(true); super.onDetachedFromWindow() }

    override fun performClick(): Boolean { super.performClick(); return true }
}
