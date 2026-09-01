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
import im.vector.app.features.attachments.editor.CropRatio
import im.vector.app.features.attachments.editor.reduceRatio
import kotlin.math.abs
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

    var rotationDegrees = 0
        private set

    /** Locked output ratio (width / height) for the crop window, or null to crop freely. */
    var aspectRatio: Float? = null
        set(value) {
            field = value
            applyRatioAroundCenter()
            invalidate()
        }

    /** Pulls the dragged crop onto the frame's center lines when it comes close. */
    var snapToCenter: Boolean = false
        set(value) {
            field = value
            if (!value) clearSnapGuides()
            invalidate()
        }

    private var videoWidth = 0
    private var videoHeight = 0
    private val crop = RectF(0f, 0f, 1f, 1f)

    /** Shape the clip will be sent at, when the sender chose one in the attachment preview. */
    var contentSizeOverride: Pair<Int, Int>? = null
        set(value) {
            if (field == value) return
            field = value
            // The shape it is measured against moved, so a locked ratio has to be re-derived.
            applyRatioAroundCenter()
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
    private val edgeHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
    }
    private val edgeHandleLength = dp(16f)
    private val snapGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }

    private val snapDistance = dp(14f)
    private val handleRadius = dp(8f)
    private val touchSlop = max(dp(24f), ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private val minNormalisedSize = 0.05f

    private enum class DragMode { NONE, PAN, CROP_MOVE, CROP_RESIZE }

    private var dragMode = DragMode.NONE
    private var dragCornerX = 0
    private var dragCornerY = 0
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val unsnappedCrop = RectF()
    private var snappedX = false
    private var snappedY = false

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
        // The turn swapped the crop's own ratio, so it has to be re-derived for the new orientation.
        applyRatioAroundCenter()
        gesture.clampPan()
        invalidate()
    }

    fun resetEdits() {
        rotationDegrees = 0
        crop.set(0f, 0f, 1f, 1f)
        gesture.reset()
        applyRatioAroundCenter()
        clearSnapGuides()
        invalidate()
    }

    /** The size the frame is shown at, which the normalised crop is measured against. */
    private fun displayedSize(): Pair<Float, Float>? {
        val sideways = rotationDegrees % 180 != 0
        val sourceWidth = (if (sideways) videoHeight else videoWidth).toFloat()
        val sourceHeight = (if (sideways) videoWidth else videoHeight).toFloat()
        val width = contentSizeOverride?.first?.toFloat() ?: sourceWidth
        val height = contentSizeOverride?.second?.toFloat() ?: sourceHeight
        return if (width > 0f && height > 0f) width to height else null
    }

    /** The frame's own ratio, reduced, to offer as the starting point for a custom one. */
    fun displayedAspectRatio(): Pair<Int, Int>? {
        val (width, height) = displayedSize() ?: return null
        return reduceRatio(width.toInt(), height.toInt())
    }

    private fun normalisedRatio(): Float? {
        val (width, height) = displayedSize() ?: return null
        return CropRatio.normalise(aspectRatio, width, height)
    }

    private fun applyRatioAroundCenter() {
        CropRatio.fitAroundCenter(crop, normalisedRatio() ?: return)
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
        drawSnapGuides(canvas)
    }

    private fun drawSnapGuides(canvas: Canvas) {
        if (dragMode == DragMode.NONE) return
        if (snappedX) canvas.drawLine(imageRect.centerX(), imageRect.top, imageRect.centerX(), imageRect.bottom, snapGuidePaint)
        if (snappedY) canvas.drawLine(imageRect.left, imageRect.centerY(), imageRect.right, imageRect.centerY(), snapGuidePaint)
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
        drawEdgeHandles(canvas, rect)
    }

    /** AOSP-gallery-style bars at the edge midpoints, marking the sides as grabbable. */
    private fun drawEdgeHandles(canvas: Canvas, rect: RectF) {
        val halfH = min(edgeHandleLength, rect.width() / 3f) / 2f
        val halfV = min(edgeHandleLength, rect.height() / 3f) / 2f
        if (halfH > 0) {
            canvas.drawLine(rect.centerX() - halfH, rect.top, rect.centerX() + halfH, rect.top, edgeHandlePaint)
            canvas.drawLine(rect.centerX() - halfH, rect.bottom, rect.centerX() + halfH, rect.bottom, edgeHandlePaint)
        }
        if (halfV > 0) {
            canvas.drawLine(rect.left, rect.centerY() - halfV, rect.left, rect.centerY() + halfV, edgeHandlePaint)
            canvas.drawLine(rect.right, rect.centerY() - halfV, rect.right, rect.centerY() + halfV, edgeHandlePaint)
        }
    }

    @Suppress("ReturnCount")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageRect.isEmpty) return false
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount >= 2) {
            // A second finger turns the gesture into zoom/pan; abandon any edit it started as.
            gesture.beginPinch(event)
            dragMode = DragMode.NONE
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (gesture.isPinching) {
            return gesture.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                clearSnapGuides()
                lastTouchX = event.x
                lastTouchY = event.y
                dragMode = beginDrag(event.x, event.y)
                if (dragMode == DragMode.CROP_MOVE) unsnappedCrop.set(crop)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DragMode.NONE) return true
                applyDrag(event.x, event.y)
                lastTouchX = event.x
                lastTouchY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = DragMode.NONE
                clearSnapGuides()
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun beginDrag(x: Float, y: Float): DragMode {
        val rect = crop.toScreen()
        val handle = nearestHandle(rect, x, y)
        if (handle != null) {
            dragCornerX = handle.first
            dragCornerY = handle.second
            return DragMode.CROP_RESIZE
        }
        // Inside the box moves it; anywhere else navigates the video, so a tall clip zoomed in is
        // not pannable only with two fingers. A box filling the frame has nowhere to move, so it
        // gives the drag up rather than swallowing it.
        if (rect.contains(x, y) && cropCanMove()) return DragMode.CROP_MOVE
        return if (gesture.zoom > 1f) DragMode.PAN else DragMode.NONE
    }

    private fun cropCanMove() = crop.width() < 0.999f || crop.height() < 0.999f

    /** Corner or side handle at (x, y): 0 = left/top edge, 1 = right/bottom, -1 = axis left alone. */
    private fun nearestHandle(rect: RectF, x: Float, y: Float): Pair<Int, Int>? {
        val horizontal = when {
            abs(x - rect.left) <= touchSlop -> 0
            abs(x - rect.right) <= touchSlop -> 1
            else -> -1
        }
        val vertical = when {
            abs(y - rect.top) <= touchSlop -> 0
            abs(y - rect.bottom) <= touchSlop -> 1
            else -> -1
        }
        return when {
            horizontal != -1 && vertical != -1 -> horizontal to vertical
            horizontal != -1 && y in rect.top..rect.bottom -> horizontal to -1
            vertical != -1 && x in rect.left..rect.right -> -1 to vertical
            else -> null
        }
    }

    private fun applyDrag(x: Float, y: Float) {
        val dx = (x - lastTouchX) / imageRect.width()
        val dy = (y - lastTouchY) / imageRect.height()
        clearSnapGuides()

        when (dragMode) {
            DragMode.PAN -> gesture.panBy(x - lastTouchX, y - lastTouchY)
            DragMode.CROP_MOVE -> moveWithSnap(dx, dy)
            DragMode.CROP_RESIZE -> {
                val nx = snapX(normalisedX(x))
                val ny = snapY(normalisedY(y))
                val k = normalisedRatio()
                if (k != null) {
                    CropRatio.resize(crop, k, nx, ny, dragCornerX, dragCornerY, minNormalisedSize, minNormalisedSize)
                } else {
                    resizeCorner(nx, ny)
                }
            }
            DragMode.NONE -> Unit
        }
    }

    private fun normalisedX(x: Float) = ((x - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)

    private fun normalisedY(y: Float) = ((y - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)

    private fun clearSnapGuides() {
        snappedX = false
        snappedY = false
    }

    /** Snapping is judged on screen distance, so it stays a fixed grab distance at any zoom. */
    private fun snapX(nx: Float): Float {
        if (!snapToCenter || imageRect.width() <= 0f) return nx
        val snapped = abs(nx - 0.5f) * imageRect.width() <= snapDistance
        if (snapped) snappedX = true
        return if (snapped) 0.5f else nx
    }

    private fun snapY(ny: Float): Float {
        if (!snapToCenter || imageRect.height() <= 0f) return ny
        val snapped = abs(ny - 0.5f) * imageRect.height() <= snapDistance
        if (snapped) snappedY = true
        return if (snapped) 0.5f else ny
    }

    /**
     * The finger moves [unsnappedCrop]; the visible box only follows it onto a center line while it
     * is close to one. Snapping the visible box instead would re-snap it on every event of a slow
     * drag, since each event's own delta stays inside the snap distance, and it could never be pulled off.
     */
    private fun moveWithSnap(dx: Float, dy: Float) {
        unsnappedCrop.offset(
                dx.coerceIn(-unsnappedCrop.left, 1f - unsnappedCrop.right),
                dy.coerceIn(-unsnappedCrop.top, 1f - unsnappedCrop.bottom)
        )
        crop.set(unsnappedCrop)
        if (!snapToCenter) return
        if (snapX(crop.centerX()) == 0.5f) crop.offset(0.5f - crop.centerX(), 0f)
        if (snapY(crop.centerY()) == 0.5f) crop.offset(0f, 0.5f - crop.centerY())
    }

    private fun resizeCorner(nx: Float, ny: Float) {
        when (dragCornerX) {
            0 -> crop.left = nx.coerceAtMost(crop.right - minNormalisedSize)
            1 -> crop.right = nx.coerceAtLeast(crop.left + minNormalisedSize)
        }
        when (dragCornerY) {
            0 -> crop.top = ny.coerceAtMost(crop.bottom - minNormalisedSize)
            1 -> crop.bottom = ny.coerceAtLeast(crop.top + minNormalisedSize)
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
