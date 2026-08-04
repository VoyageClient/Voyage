/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.graphics.RectF
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import kotlin.math.hypot
import kotlin.math.max

/**
 * Cropping by the Instagram model: the window stays put and the video moves underneath it, so what
 * the user ends up with is exactly the normalised rectangle the GL exporter wants. The window is a
 * clipping container holding the [TextureView]; zoom and pan go into the view's transform matrix.
 */
class VideoCropController(
        private val container: View,
        private val window: View,
        private val textureView: TextureView,
        private val frame: View,
        private val onTap: () -> Unit,
) {

    private var videoWidth = 0
    private var videoHeight = 0
    private var pendingCrop: RectF? = null

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    /** Width over height of the crop window, or null to keep the video's own. */
    var aspectRatio: Float? = null
        set(value) {
            field = value
            // Changing the shape of the window moves the picture under it; a stale pan would sit
            // outside the new bounds.
            panX = 0f
            panY = 0f
            layout()
        }

    var rotationDegrees: Int = 0
        set(value) {
            field = value
            panX = 0f
            panY = 0f
            layout()
        }

    init {
        attachGestures()
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!applyPendingCrop()) layout()
        }
        // The matrix is expressed against the window's size, which the crop window only takes on at
        // the next layout pass — re-apply once it has.
        textureView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            geometry()?.let { applyTransform(it) }
        }
    }

    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        if (!applyPendingCrop()) layout()
    }

    fun reset() {
        rotationDegrees = 0
        resetCrop()
    }

    private fun resetCrop() {
        pendingCrop = null
        zoom = 1f
        panX = 0f
        panY = 0f
        aspectRatio = null
    }

    /** The kept region of the displayed frame, or null when the whole of it is kept. */
    val cropRect: RectF?
        get() {
            val geometry = geometry() ?: return null
            val rect = RectF(
                    ((geometry.imageWidth / 2f - panX - geometry.windowWidth / 2f) / geometry.imageWidth),
                    ((geometry.imageHeight / 2f - panY - geometry.windowHeight / 2f) / geometry.imageHeight),
                    0f, 0f
            )
            rect.right = rect.left + geometry.windowWidth / geometry.imageWidth
            rect.bottom = rect.top + geometry.windowHeight / geometry.imageHeight
            rect.set(
                    rect.left.coerceIn(0f, 1f),
                    rect.top.coerceIn(0f, 1f),
                    rect.right.coerceIn(0f, 1f),
                    rect.bottom.coerceIn(0f, 1f)
            )
            val whole = rect.left <= EDGE_EPSILON && rect.top <= EDGE_EPSILON &&
                    rect.right >= 1f - EDGE_EPSILON && rect.bottom >= 1f - EDGE_EPSILON
            return rect.takeUnless { whole }
        }

    /** Replays a saved [cropRect] by solving back to the window shape, zoom and pan that produce it. */
    fun applyCropRect(rect: RectF?) {
        if (rect == null || rect.width() <= 0f || rect.height() <= 0f) {
            resetCrop()
            return
        }
        // Restoring runs before the video is measured, let alone laid out, so it waits its turn.
        pendingCrop = RectF(rect)
        applyPendingCrop()
    }

    private fun applyPendingCrop(): Boolean {
        val rect = pendingCrop ?: return false
        val rotatedWidth = rotatedWidth()
        val rotatedHeight = rotatedHeight()
        if (rotatedWidth <= 0f || rotatedHeight <= 0f) return false
        aspectRatio = (rect.width() * rotatedWidth) / (rect.height() * rotatedHeight)
        val geometry = geometry() ?: return false
        pendingCrop = null
        val imageWidth = geometry.windowWidth / rect.width()
        val imageHeight = geometry.windowHeight / rect.height()
        zoom = (imageWidth / rotatedWidth) / geometry.coverScale
        panX = imageWidth / 2f - geometry.windowWidth / 2f - rect.left * imageWidth
        panY = imageHeight / 2f - geometry.windowHeight / 2f - rect.top * imageHeight
        layout()
        return true
    }

    /** Sizes the crop window inside the container and re-applies the transform under it. */
    private fun layout() {
        val geometry = geometry() ?: return
        listOf(window, frame).forEach { view ->
            val params = view.layoutParams as? FrameLayout.LayoutParams ?: return@forEach
            params.width = geometry.windowWidth.toInt()
            params.height = geometry.windowHeight.toInt()
            view.layoutParams = params
        }
        clampPan(geometry)
        applyTransform(geometry)
    }

    private class Geometry(
            val windowWidth: Float,
            val windowHeight: Float,
            val coverScale: Float,
            val scale: Float,
            val imageWidth: Float,
            val imageHeight: Float,
    )

    private fun rotatedWidth() = (if (rotationDegrees % 180 == 90) videoHeight else videoWidth).toFloat()
    private fun rotatedHeight() = (if (rotationDegrees % 180 == 90) videoWidth else videoHeight).toFloat()

    private fun geometry(): Geometry? {
        val rotatedWidth = rotatedWidth()
        val rotatedHeight = rotatedHeight()
        if (rotatedWidth <= 0f || rotatedHeight <= 0f) return null
        val containerWidth = container.width.toFloat()
        val containerHeight = container.height.toFloat()
        if (containerWidth <= 0f || containerHeight <= 0f) return null

        val windowAspect = aspectRatio ?: (rotatedWidth / rotatedHeight)
        val windowWidth: Float
        val windowHeight: Float
        if (containerWidth / containerHeight > windowAspect) {
            windowHeight = containerHeight
            windowWidth = windowHeight * windowAspect
        } else {
            windowWidth = containerWidth
            windowHeight = windowWidth / windowAspect
        }
        // The window is always filled: anything less would export blank bars.
        val coverScale = max(windowWidth / rotatedWidth, windowHeight / rotatedHeight)
        val scale = coverScale * zoom
        return Geometry(windowWidth, windowHeight, coverScale, scale, rotatedWidth * scale, rotatedHeight * scale)
    }

    /** The guides are worth showing while a gesture is in flight, and while a crop is in effect. */
    private fun updateFrameVisibility() {
        frame.visibility = if (gesturing || cropRect != null) View.VISIBLE else View.GONE
    }

    private fun clampPan(geometry: Geometry) {
        val maxX = max(0f, (geometry.imageWidth - geometry.windowWidth) / 2f)
        val maxY = max(0f, (geometry.imageHeight - geometry.windowHeight) / 2f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    private fun applyTransform(geometry: Geometry) {
        val width = geometry.windowWidth
        val height = geometry.windowHeight
        if (width <= 0f || height <= 0f) return
        val matrix = Matrix()
        // The surface is stretched to fill the view, so the scale here is the drawn size over it.
        matrix.setScale(videoWidth * geometry.scale / width, videoHeight * geometry.scale / height, width / 2f, height / 2f)
        matrix.postRotate(rotationDegrees.toFloat(), width / 2f, height / 2f)
        matrix.postTranslate(panX, panY)
        textureView.setTransform(matrix)
        textureView.invalidate()
        updateFrameVisibility()
    }

    private var pointerSpan = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var pinching = false
    private var gesturing = false

    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures() {
        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    moved = false
                    pinching = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    pointerSpan = spanOf(event)
                    pinching = true
                    moved = true
                    gesturing = true
                    updateFrameVisibility()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pinching && event.pointerCount >= 2) {
                        // Tracked from the raw span rather than ScaleGestureDetector, which stops
                        // reporting once the fingers converge inside its minimum span.
                        val span = spanOf(event)
                        if (pointerSpan > 0f && span > 0f) {
                            setZoom(zoom * span / pointerSpan)
                            pointerSpan = span
                        }
                    } else if (!pinching) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        if (!moved && hypot(dx, dy) < TOUCH_SLOP_PX) return@setOnTouchListener true
                        moved = true
                        gesturing = true
                        panX += dx
                        panY += dy
                        lastX = event.x
                        lastY = event.y
                        geometry()?.let {
                            clampPan(it)
                            applyTransform(it)
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // Carry on as a pan from whichever finger is left, instead of jumping to it.
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                    if (event.pointerCount <= 2) pinching = false
                }
                MotionEvent.ACTION_UP -> {
                    gesturing = false
                    updateFrameVisibility()
                    if (!moved) onTap()
                }
                MotionEvent.ACTION_CANCEL -> {
                    gesturing = false
                    updateFrameVisibility()
                }
            }
            true
        }
    }

    private fun setZoom(value: Float) {
        zoom = value.coerceIn(1f, MAX_ZOOM)
        geometry()?.let {
            clampPan(it)
            applyTransform(it)
        }
    }

    private fun spanOf(event: MotionEvent): Float {
        return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }

    companion object {
        private const val MAX_ZOOM = 6f
        private const val TOUCH_SLOP_PX = 12f
        private const val EDGE_EPSILON = 0.002f
    }
}
