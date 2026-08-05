/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatImageView
import im.vector.app.features.attachments.ZoomPanGesture
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
            // A video's still is shown with zoom off, and FIT_CENTER ignores the matrix entirely.
            applyScaleType()
            updateBaseMatrix()
        }

    private fun applyScaleType() {
        scaleType = if (zoomEnabled || contentSizeOverride != null) ScaleType.MATRIX else ScaleType.FIT_CENTER
    }

    private val baseMatrix = Matrix()
    private val drawMatrix = Matrix()

    private val gesture = ZoomPanGesture(MIN_ZOOM, MAX_ZOOM, springBackBelowFit = true) { applyMatrix() }.apply {
        onDisallowIntercept = { parent?.requestDisallowInterceptTouchEvent(it) }
        onTap = { performClick() }
    }

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
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
}
