/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import im.vector.app.features.attachments.ZoomPanGesture
import org.matrix.android.sdk.api.debug.DebugLog
import kotlin.math.min

/** Rubber-band floor only; releasing below fit springs back to 1x. */
private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 20f

/** Zoomable image for the attachment pager. */
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
            applyScaleType()
            if (value) updateBaseMatrix() else resetZoom()
        }

    /**
     * Shape to present the image at rather than its own, so the preview matches the size it will be
     * sent at. A size the sender chose against the source's aspect ratio really does stretch it —
     * that is what the recipient will get.
     */
    var contentSizeOverride: Pair<Int, Int>? = null
        set(value) {
            if (field == value) return
            field = value
            clearResolutionBuffer()
            // A video's still is shown with zoom off, and FIT_CENTER ignores the matrix entirely.
            applyScaleType()
            updateBaseMatrix()
            invalidate()
        }

    private fun applyScaleType() {
        scaleType = if (zoomEnabled || contentSizeOverride != null) ScaleType.MATRIX else ScaleType.FIT_CENTER
    }

    private val baseMatrix = Matrix()
    private val drawMatrix = Matrix()
    private var resolutionBuffer: Bitmap? = null
    private var resolutionCanvas: Canvas? = null
    private val resolutionPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val animationFrame = Runnable { invalidate() }
    private var renderedFrames = 0

    private val gesture = ZoomPanGesture(MIN_ZOOM, MAX_ZOOM, springBackBelowFit = true) { applyMatrix() }.apply {
        onDisallowIntercept = { parent?.requestDisallowInterceptTouchEvent(it) }
        onTap = { performClick() }
    }

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        removeCallbacks(animationFrame)
        super.setImageDrawable(drawable)
        gesture.reset()
        updateBaseMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateBaseMatrix()
    }

    fun resetZoom() {
        gesture.reset()
        applyMatrix()
    }

    override fun onDraw(canvas: Canvas) {
        val size = contentSizeOverride
        val image = drawable
        if (size == null || size.first <= 0 || size.second <= 0 || image == null || image.bounds.isEmpty) {
            super.onDraw(canvas)
            return
        }
        val maxSide = maxOf(image.bounds.width(), image.bounds.height(), width, height, 1)
        val scale = minOf(1f, maxSide.toFloat() / maxOf(size.first, size.second))
        val bufferWidth = (size.first * scale).toInt().coerceAtLeast(1)
        val bufferHeight = (size.second * scale).toInt().coerceAtLeast(1)
        var buffer = resolutionBuffer
        if (buffer == null || buffer.width != bufferWidth || buffer.height != bufferHeight) {
            clearResolutionBuffer()
            buffer = createBitmap(bufferWidth, bufferHeight)
            resolutionBuffer = buffer
            resolutionCanvas = Canvas(buffer)
            renderedFrames = 0
            DebugLog.i { "UPLOADPREVIEW image buffer=${bufferWidth}x$bufferHeight target=${size.first}x${size.second} drawable=${image.javaClass.simpleName}" }
        }
        val target = resolutionCanvas ?: return
        target.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val savedTarget = target.save()
        target.scale(bufferWidth.toFloat() / image.bounds.width(), bufferHeight.toFloat() / image.bounds.height())
        target.translate(-image.bounds.left.toFloat(), -image.bounds.top.toFloat())
        try {
            image.draw(target)
        } finally {
            target.restoreToCount(savedTarget)
        }
        canvas.withTranslation(paddingLeft.toFloat(), paddingTop.toFloat()) {
            concat(imageMatrix)
            drawBitmap(buffer, null, image.bounds, resolutionPaint)
        }
        removeCallbacks(animationFrame)
        if ((image as? Animatable)?.isRunning == true) {
            // Native GIFs normally redraw on RenderThread; a bitmap preview needs UI-thread redraws.
            postDelayed(animationFrame, ANIMATION_FRAME_MS)
            if (++renderedFrames % 60 == 0) {
                DebugLog.i { "UPLOADPREVIEW animation frames=$renderedFrames drawable=${image.javaClass.simpleName} running=true" }
            }
        }
    }

    private fun clearResolutionBuffer() {
        removeCallbacks(animationFrame)
        resolutionCanvas?.setBitmap(null)
        resolutionCanvas = null
        resolutionBuffer = null
    }

    override fun onDetachedFromWindow() {
        clearResolutionBuffer()
        super.onDetachedFromWindow()
    }

    private fun updateBaseMatrix() {
        val current = drawable ?: return
        val intrinsicWidth = current.intrinsicWidth.toFloat()
        val intrinsicHeight = current.intrinsicHeight.toFloat()
        if (intrinsicWidth <= 0f || intrinsicHeight <= 0f || width == 0 || height == 0) return
        val shapeWidth = contentSizeOverride?.first?.toFloat() ?: intrinsicWidth
        val shapeHeight = contentSizeOverride?.second?.toFloat() ?: intrinsicHeight
        val scale = min(width / shapeWidth, height / shapeHeight)
        gesture.contentWidth = shapeWidth * scale
        gesture.contentHeight = shapeHeight * scale
        gesture.viewportWidth = width.toFloat()
        gesture.viewportHeight = height.toFloat()
        baseMatrix.reset()
        // Non-uniform whenever the target shape differs, which is exactly the point.
        baseMatrix.postScale(gesture.contentWidth / intrinsicWidth, gesture.contentHeight / intrinsicHeight)
        baseMatrix.postTranslate((width - gesture.contentWidth) / 2f, (height - gesture.contentHeight) / 2f)
        applyMatrix()
    }

    private fun applyMatrix() {
        drawMatrix.set(baseMatrix)
        drawMatrix.postScale(gesture.zoom, gesture.zoom, width / 2f, height / 2f)
        drawMatrix.postTranslate(gesture.panX, gesture.panY)
        imageMatrix = drawMatrix
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!zoomEnabled || drawable == null) return super.onTouchEvent(event)
        return gesture.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        const val ANIMATION_FRAME_MS = 16L
    }
}
