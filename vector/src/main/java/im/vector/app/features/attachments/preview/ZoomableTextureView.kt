/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.TextureView
import im.vector.app.features.attachments.ZoomPanGesture
import kotlin.math.min

private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 20f

/**
 * Video counterpart of [ZoomableImageView], so a clip can be inspected before sending it the same
 * way a photo can. A [TextureView] stretches its surface to fill the view, so even the aspect fit
 * is a transform matrix; zoom and pan compose on top of it.
 */
class ZoomableTextureView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr) {

    /** See [ZoomableImageView.contentSizeOverride]. */
    var contentSizeOverride: Pair<Int, Int>? = null
        set(value) {
            if (field == value) return
            field = value
            applyMatrix()
        }

    private val drawMatrix = Matrix()
    private var videoWidth = 0
    private var videoHeight = 0

    private val gesture = ZoomPanGesture(MIN_ZOOM, MAX_ZOOM, springBackBelowFit = true) { applyMatrix() }.apply {
        onDisallowIntercept = { parent?.requestDisallowInterceptTouchEvent(it) }
        onTap = { performClick() }
    }

    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        applyMatrix()
    }

    fun resetZoom() {
        gesture.reset()
        applyMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyMatrix()
    }

    private fun applyMatrix() {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || videoWidth <= 0 || videoHeight <= 0) return
        val shapeWidth = contentSizeOverride?.first ?: videoWidth
        val shapeHeight = contentSizeOverride?.second ?: videoHeight
        val fitScale = min(viewWidth / shapeWidth, viewHeight / shapeHeight)
        gesture.contentWidth = shapeWidth * fitScale
        gesture.contentHeight = shapeHeight * fitScale
        gesture.viewportWidth = viewWidth
        gesture.viewportHeight = viewHeight

        val drawnWidth = gesture.contentWidth * gesture.zoom
        val drawnHeight = gesture.contentHeight * gesture.zoom
        drawMatrix.reset()
        drawMatrix.setScale(drawnWidth / viewWidth, drawnHeight / viewHeight)
        drawMatrix.postTranslate((viewWidth - drawnWidth) / 2f + gesture.panX, (viewHeight - drawnHeight) / 2f + gesture.panY)
        setTransform(drawMatrix)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gesture.onTouchEvent(event) || super.onTouchEvent(event)
    }
}
