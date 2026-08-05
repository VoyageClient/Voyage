/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import im.vector.app.features.attachments.ZoomPanGesture
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Below 1x, so the video can be shrunk to leave room around handles that sit on its edge. */
private const val MIN_ZOOM = 0.15f
private const val MAX_ZOOM = 20f

private const val EDGE_INSET_FRACTION = 0.06f

/**
 * The video editor's crop window: a freeform rectangle with corner handles, drawn over the
 * [android.view.TextureView] playing the clip. One geometry drives both, so the overlay hands back
 * the matrix the surface should be drawn with.
 *
 * The same shape and gestures as the image editor's `ImageEditorView`, minus the censor tool. The
 * crop is normalised (0..1) against the *displayed* frame, so it survives rotation and is exactly
 * what the exporter's GL stage wants.
 */
class VideoCropOverlayView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Raised with the transform the video surface should adopt. */
    var onTransform: ((Matrix) -> Unit)? = null

    /** Raised on a touch that changed nothing, i.e. a request to play or pause. */
    var onTap: (() -> Unit)? = null

    var rotationDegrees = 0
        private set

    private var videoWidth = 0
    private var videoHeight = 0
    private val crop = RectF(0f, 0f, 1f, 1f)

    /** Shape the clip will be sent at, when the sender chose one in the attachment preview. */
    var contentSizeOverride: Pair<Int, Int>? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val imageRect = RectF()
    private val surfaceMatrix = Matrix()

    private val dimPaint = Paint().apply { color = 0xB0000000.toInt() }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val handleRadius = dp(8f)
    private val touchSlop = max(dp(24f), ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private val minNormalisedSize = 0.05f

    private enum class DragMode { NONE, PAN, CROP_MOVE, CROP_RESIZE }

    private var dragMode = DragMode.NONE
    private var dragCornerX = 0
    private var dragCornerY = 0
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downX = 0f
    private var downY = 0f
    private var movedDuringGesture = false
    private val tapSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val gesture = ZoomPanGesture(MIN_ZOOM, MAX_ZOOM) { invalidate() }.apply {
        onDisallowIntercept = { parent?.requestDisallowInterceptTouchEvent(it) }
    }

    private val lastSurfaceMatrix = Matrix()

    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        invalidate()
    }

    fun rotateClockwise() {
        rotationDegrees = (rotationDegrees + 90) % 360
        rotateNormalised(crop)
        gesture.clampPan()
        invalidate()
    }

    fun resetEdits() {
        rotationDegrees = 0
        crop.set(0f, 0f, 1f, 1f)
        gesture.reset()
        invalidate()
    }

    /** The kept region of the displayed frame, or null when the whole of it is kept. */
    fun currentCrop(): RectF? {
        val whole = crop.left <= 0f && crop.top <= 0f && crop.right >= 1f && crop.bottom >= 1f
        return if (whole) null else RectF(crop)
    }

    fun restoreEdits(rotation: Int, savedCrop: RectF?) {
        rotationDegrees = ((rotation % 360) + 360) % 360
        crop.set(savedCrop ?: RectF(0f, 0f, 1f, 1f))
        invalidate()
    }

    /** A 90 degree clockwise turn maps (x, y) to (1 - y, x). */
    private fun rotateNormalised(rect: RectF) {
        rect.set(1f - rect.bottom, rect.left, 1f - rect.top, rect.right)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!computeGeometry()) return

        val cropScreen = crop.toScreen()
        drawDimOutside(canvas, cropScreen)
        canvas.drawRect(cropScreen, borderPaint)
        drawThirds(canvas, cropScreen)
        drawCornerHandles(canvas, cropScreen)
    }

    private fun computeGeometry(): Boolean {
        val sideways = rotationDegrees % 180 != 0
        val sourceWidth = (if (sideways) videoHeight else videoWidth).toFloat()
        val sourceHeight = (if (sideways) videoWidth else videoHeight).toFloat()
        // The shape it will be sent at, so the editor shows the same picture the previewer does.
        val shapeWidth = contentSizeOverride?.first?.toFloat() ?: sourceWidth
        val shapeHeight = contentSizeOverride?.second?.toFloat() ?: sourceHeight
        // Inset the video so handles sitting on its edge aren't jammed against the screen edge.
        // Pinching out below 1x gives more room than this when a shot needs it.
        val pad = max(dp(28f), min(width, height) * EDGE_INSET_FRACTION)
        val availableWidth = width - pad * 2
        val availableHeight = height - pad * 2
        if (availableWidth <= 0 || availableHeight <= 0 || shapeWidth <= 0 || shapeHeight <= 0) return false

        val fittedScale = min(availableWidth / shapeWidth, availableHeight / shapeHeight)
        gesture.contentWidth = shapeWidth * fittedScale
        gesture.contentHeight = shapeHeight * fittedScale
        gesture.viewportWidth = width.toFloat()
        gesture.viewportHeight = height.toFloat()

        val scale = fittedScale * gesture.zoom
        val drawnWidth = shapeWidth * scale
        val drawnHeight = shapeHeight * scale
        val centreX = width / 2f + gesture.panX
        val centreY = height / 2f + gesture.panY
        imageRect.set(centreX - drawnWidth / 2f, centreY - drawnHeight / 2f, centreX + drawnWidth / 2f, centreY + drawnHeight / 2f)

        // The surface is stretched to fill the view, so this scales against the view, not the frame.
        // Rotation comes after, so the box has to be given to it the other way round when sideways.
        val unrotatedWidth = if (sideways) drawnHeight else drawnWidth
        val unrotatedHeight = if (sideways) drawnWidth else drawnHeight
        surfaceMatrix.reset()
        surfaceMatrix.setScale(unrotatedWidth / width, unrotatedHeight / height, width / 2f, height / 2f)
        surfaceMatrix.postRotate(rotationDegrees.toFloat(), width / 2f, height / 2f)
        surfaceMatrix.postTranslate(gesture.panX, gesture.panY)
        // Only on a real change: this runs every draw, and setTransform invalidates the surface.
        if (surfaceMatrix != lastSurfaceMatrix) {
            lastSurfaceMatrix.set(surfaceMatrix)
            onTransform?.invoke(surfaceMatrix)
        }
        return true
    }

    private fun drawDimOutside(canvas: Canvas, rect: RectF) {
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, rect.top, dimPaint)
        canvas.drawRect(imageRect.left, rect.bottom, imageRect.right, imageRect.bottom, dimPaint)
        canvas.drawRect(imageRect.left, rect.top, rect.left, rect.bottom, dimPaint)
        canvas.drawRect(rect.right, rect.top, imageRect.right, rect.bottom, dimPaint)
    }

    private fun drawThirds(canvas: Canvas, rect: RectF) {
        val thirdWidth = rect.width() / 3f
        val thirdHeight = rect.height() / 3f
        for (index in 1..2) {
            canvas.drawLine(rect.left + thirdWidth * index, rect.top, rect.left + thirdWidth * index, rect.bottom, gridPaint)
            canvas.drawLine(rect.left, rect.top + thirdHeight * index, rect.right, rect.top + thirdHeight * index, gridPaint)
        }
    }

    private fun drawCornerHandles(canvas: Canvas, rect: RectF) {
        for (x in listOf(rect.left, rect.right)) {
            for (y in listOf(rect.top, rect.bottom)) {
                canvas.drawCircle(x, y, handleRadius, handlePaint)
            }
        }
    }

    @Suppress("ReturnCount")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageRect.isEmpty) return false
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount >= 2) {
            // A second finger turns the gesture into zoom/pan; abandon any edit it started as.
            gesture.beginPinch(event)
            dragMode = DragMode.NONE
            movedDuringGesture = true
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (gesture.isPinching) {
            return gesture.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastTouchX = event.x
                lastTouchY = event.y
                downX = event.x
                downY = event.y
                movedDuringGesture = false
                dragMode = beginDrag(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DragMode.NONE) return true
                if (hypot(event.x - downX, event.y - downY) > tapSlop) movedDuringGesture = true
                applyDrag(event.x, event.y)
                lastTouchX = event.x
                lastTouchY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = DragMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!movedDuringGesture && event.actionMasked == MotionEvent.ACTION_UP) onTap?.invoke()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun beginDrag(x: Float, y: Float): DragMode {
        val rect = crop.toScreen()
        val corner = nearestCorner(rect, x, y)
        if (corner != null) {
            dragCornerX = corner.first
            dragCornerY = corner.second
            return DragMode.CROP_RESIZE
        }
        // Once zoomed, dragging navigates the video — otherwise a tall clip zoomed in would only be
        // pannable with two fingers. At 1x there is nowhere to pan, so drag moves the crop box.
        if (gesture.zoom > 1f) return DragMode.PAN
        return if (rect.contains(x, y)) DragMode.CROP_MOVE else DragMode.NONE
    }

    private fun nearestCorner(rect: RectF, x: Float, y: Float): Pair<Int, Int>? {
        val horizontal = when {
            abs(x - rect.left) <= touchSlop -> 0
            abs(x - rect.right) <= touchSlop -> 1
            else -> return null
        }
        val vertical = when {
            abs(y - rect.top) <= touchSlop -> 0
            abs(y - rect.bottom) <= touchSlop -> 1
            else -> return null
        }
        return horizontal to vertical
    }

    private fun applyDrag(x: Float, y: Float) {
        val dx = (x - lastTouchX) / imageRect.width()
        val dy = (y - lastTouchY) / imageRect.height()
        val nx = ((x - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
        val ny = ((y - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)

        when (dragMode) {
            DragMode.PAN -> gesture.panBy(x - lastTouchX, y - lastTouchY)
            DragMode.CROP_MOVE -> {
                val clampedDx = dx.coerceIn(-crop.left, 1f - crop.right)
                val clampedDy = dy.coerceIn(-crop.top, 1f - crop.bottom)
                crop.offset(clampedDx, clampedDy)
            }
            DragMode.CROP_RESIZE -> resizeCorner(nx, ny)
            DragMode.NONE -> Unit
        }
    }

    private fun resizeCorner(nx: Float, ny: Float) {
        if (dragCornerX == 0) {
            crop.left = nx.coerceAtMost(crop.right - minNormalisedSize)
        } else {
            crop.right = nx.coerceAtLeast(crop.left + minNormalisedSize)
        }
        if (dragCornerY == 0) {
            crop.top = ny.coerceAtMost(crop.bottom - minNormalisedSize)
        } else {
            crop.bottom = ny.coerceAtLeast(crop.top + minNormalisedSize)
        }
    }

    private fun RectF.toScreen() = RectF(
            imageRect.left + left * imageRect.width(),
            imageRect.top + top * imageRect.height(),
            imageRect.left + right * imageRect.width(),
            imageRect.top + bottom * imageRect.height()
    )

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
