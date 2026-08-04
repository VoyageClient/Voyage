/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.image

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val ROTATION_ANIMATION_MS = 140L

/** Below 1x, so the image can be shrunk to leave room around handles that sit on its edge. */
private const val MIN_ZOOM = 0.15f

/** Deliberately deeper than the media viewer's 6x, so small details can be censored precisely. */
private const val MAX_ZOOM = 20f

private const val EDGE_INSET_FRACTION = 0.06f

/**
 * Floor for the pinch span. Fingers can meet or cross, and the span is an absolute distance, so it
 * collapses toward zero and grows again; below this the ratio is also dominated by touch noise.
 */
private const val MIN_PINCH_SPAN_PX = 32f

private const val MAX_PINCH_RATIO_PER_FRAME = 2f

/**
 * Renders the image being edited plus its crop window and censor rectangles, and turns touches
 * into edits. All rectangles are kept normalised (0..1) against the *displayed* image, so they
 * survive rotation and can be replayed at full resolution by [ImageEditorExporter].
 */
class ImageEditorView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Tool { CROP, CENSOR }

    var tool: Tool = Tool.CROP
        set(value) {
            field = value
            selectedCensor = -1
            invalidate()
        }

    /** Raised when the view changes tool on its own, so the host can re-style its buttons. */
    var onToolChanged: ((Tool) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private var userRotation = 0
    private val crop = RectF(0f, 0f, 1f, 1f)
    private val censors = mutableListOf<RectF>()
    private var selectedCensor = -1

    private val imageRect = RectF()
    private val drawMatrix = Matrix()

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val censorPaint = Paint().apply { color = Color.BLACK }
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
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
    }

    private val badgeBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    private val badgeCrossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    private val handleRadius = dp(8f)
    private val badgeRadius = dp(12f)
    private val touchSlop = max(dp(24f), ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private val minNormalisedSize = 0.05f

    private var dragMode = DragMode.NONE
    private var dragCornerX = 0
    private var dragCornerY = 0
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var createAnchorX = 0f
    private var createAnchorY = 0f
    private var animatedRotation = 0f
    private var rotationAnimator: ValueAnimator? = null

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    /** Size the image occupies at 1x. Zoom-independent, so clamping never lags a frame behind. */
    private var fittedWidth = 0f
    private var fittedHeight = 0f
    private var lastSpan = 0f
    private var pinchActive = false

    /**
     * Pinch is tracked from the raw pointer span rather than ScaleGestureDetector, which stops
     * reporting once the fingers converge inside its ~27mm minimum span — the point you reach part
     * way through zooming out.
     */
    private fun applyPinch(event: MotionEvent) {
        // Floored rather than dropping the frame, which would leave a stale lastSpan for the next
        // one to divide by.
        val span = max(spanOf(event), MIN_PINCH_SPAN_PX)
        val previousZoom = zoom
        // A per-frame safety net; a legitimate pinch never doubles the span between touch events.
        val ratio = (span / lastSpan).coerceIn(1f / MAX_PINCH_RATIO_PER_FRAME, MAX_PINCH_RATIO_PER_FRAME)
        zoom = (zoom * ratio).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val factor = zoom / previousZoom
        val focusX = focusX(event)
        val focusY = focusY(event)
        // Scale about the focus point only. Following the focus as well would turn a pinch into a
        // pan, because holding one finger still drags the midpoint toward it as the other moves.
        val centreX = width / 2f + panX
        val centreY = height / 2f + panY
        panX = focusX - factor * (focusX - centreX) - width / 2f
        panY = focusY - factor * (focusY - centreY) - height / 2f
        lastSpan = span
        clampPan()
        invalidate()
    }

    private fun spanOf(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }

    private fun focusX(event: MotionEvent) =
            if (event.pointerCount < 2) event.x else (event.getX(0) + event.getX(1)) / 2f

    private fun focusY(event: MotionEvent) =
            if (event.pointerCount < 2) event.y else (event.getY(0) + event.getY(1)) / 2f

    private fun beginPinch(event: MotionEvent) {
        pinchActive = true
        // The first finger already staked out a zero-size censor; abandoning the drag here would
        // otherwise strand it in the list, invisible but enough to count as an edit.
        if (dragMode == DragMode.CENSOR_CREATE) discardPendingCensor()
        dragMode = DragMode.NONE
        lastSpan = max(spanOf(event), MIN_PINCH_SPAN_PX)
    }

    private fun discardPendingCensor() {
        if (selectedCensor !in censors.indices) return
        censors.removeAt(selectedCensor)
        selectedCensor = -1
    }

    /**
     * Mirrors the media viewer's clampUserTranslation: once the image is no larger than the
     * viewport it is forced back to centre. Anchoring a pinch-out on an off-centre focus otherwise
     * shrinks the image *toward* that point, which reads as the zoom turning into a pan.
     */
    private fun clampPan() {
        if (zoom <= 1f) {
            panX = 0f
            panY = 0f
            return
        }
        val maxX = max(0f, (fittedWidth * zoom - width) / 2f)
        val maxY = max(0f, (fittedHeight * zoom - height) / 2f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    private enum class DragMode { NONE, PAN, CROP_MOVE, CROP_RESIZE, CENSOR_MOVE, CENSOR_RESIZE, CENSOR_CREATE }

    fun setBitmap(value: Bitmap) {
        bitmap = value
        requestLayout()
        invalidate()
    }

    fun rotateClockwise() {
        userRotation = (userRotation + 90) % 360
        rotateNormalised(crop)
        censors.forEach { rotateNormalised(it) }
        clampPan()
        animateRotation()
    }

    /**
     * The geometry snaps to the new orientation immediately; this just spins the last quarter turn
     * back in so the change reads as a rotation rather than a jump.
     */
    private fun animateRotation() {
        rotationAnimator?.cancel()
        rotationAnimator = ValueAnimator.ofFloat(-90f, 0f).apply {
            duration = ROTATION_ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animatedRotation = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animatedRotation = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    fun resetEdits() {
        userRotation = 0
        crop.set(0f, 0f, 1f, 1f)
        censors.clear()
        selectedCensor = -1
        zoom = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    fun deleteSelectedCensor() {
        if (selectedCensor !in censors.indices) return
        censors.removeAt(selectedCensor)
        selectedCensor = -1
        invalidate()
    }

    fun currentEdits() = ImageEditorEdits(
            userRotation = userRotation,
            crop = RectF(crop),
            censors = censors.map { RectF(it) }
    )

    fun restoreEdits(edits: ImageEditorEdits) {
        userRotation = edits.userRotation
        crop.set(edits.crop)
        censors.clear()
        censors.addAll(edits.censors.map { RectF(it) })
        selectedCensor = -1
        invalidate()
    }

    /** A 90 degree clockwise turn maps (x, y) to (1 - y, x). */
    private fun rotateNormalised(rect: RectF) {
        rect.set(1f - rect.bottom, rect.left, 1f - rect.top, rect.right)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        computeGeometry(bmp)

        // Spin the whole composition, so the overlays stay locked to the image mid-animation.
        val spinning = animatedRotation != 0f
        if (spinning) {
            canvas.save()
            canvas.rotate(animatedRotation, width / 2f, height / 2f)
        }

        canvas.drawBitmap(bmp, drawMatrix, bitmapPaint)

        censors.forEach { canvas.drawRect(it.toScreen(), censorPaint) }

        val cropScreen = crop.toScreen()
        drawDimOutside(canvas, cropScreen)

        if (tool == Tool.CROP) {
            canvas.drawRect(cropScreen, borderPaint)
            drawThirds(canvas, cropScreen)
            drawCornerHandles(canvas, cropScreen)
        } else {
            canvas.drawRect(cropScreen, gridPaint)
            censors.getOrNull(selectedCensor)?.let { selected ->
                val r = selected.toScreen()
                canvas.drawRect(r, selectionPaint)
                drawCornerHandles(canvas, r, skipTopRight = true)
                drawDeleteBadge(canvas, r)
            }
        }

        if (spinning) canvas.restore()
    }

    private fun computeGeometry(bmp: Bitmap) {
        val sideways = userRotation % 180 != 0
        val srcW = (if (sideways) bmp.height else bmp.width).toFloat()
        val srcH = (if (sideways) bmp.width else bmp.height).toFloat()
        // Inset the image so handles sitting on its edge aren't jammed against the screen edge.
        // Pinching out below 1x gives more room than this when a shot needs it.
        val pad = max(dp(28f), min(width, height) * EDGE_INSET_FRACTION)
        val availableW = width - pad * 2
        val availableH = height - pad * 2
        if (availableW <= 0 || availableH <= 0 || srcW <= 0 || srcH <= 0) return
        // Zooming imageRect is enough for the whole screen: the crop window, censors and handles
        // are all projected through it.
        val fittedScale = min(availableW / srcW, availableH / srcH)
        fittedWidth = srcW * fittedScale
        fittedHeight = srcH * fittedScale
        val scale = fittedScale * zoom
        val drawnW = srcW * scale
        val drawnH = srcH * scale
        val cx = width / 2f + panX
        val cy = height / 2f + panY
        imageRect.set(cx - drawnW / 2f, cy - drawnH / 2f, cx + drawnW / 2f, cy + drawnH / 2f)

        drawMatrix.reset()
        drawMatrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        drawMatrix.postRotate(userRotation.toFloat())
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(cx, cy)
    }

    private fun drawDimOutside(canvas: Canvas, rect: RectF) {
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, rect.top, dimPaint)
        canvas.drawRect(imageRect.left, rect.bottom, imageRect.right, imageRect.bottom, dimPaint)
        canvas.drawRect(imageRect.left, rect.top, rect.left, rect.bottom, dimPaint)
        canvas.drawRect(rect.right, rect.top, imageRect.right, rect.bottom, dimPaint)
    }

    private fun drawThirds(canvas: Canvas, rect: RectF) {
        val thirdW = rect.width() / 3f
        val thirdH = rect.height() / 3f
        for (i in 1..2) {
            canvas.drawLine(rect.left + thirdW * i, rect.top, rect.left + thirdW * i, rect.bottom, gridPaint)
            canvas.drawLine(rect.left, rect.top + thirdH * i, rect.right, rect.top + thirdH * i, gridPaint)
        }
    }

    /** Takes the place of the top-right resize handle rather than sitting on top of it. */
    private fun deleteBadgeCentre(rect: RectF) = rect.right to rect.top

    private fun drawDeleteBadge(canvas: Canvas, rect: RectF) {
        val (cx, cy) = deleteBadgeCentre(rect)
        canvas.drawCircle(cx, cy, badgeRadius, badgeBackgroundPaint)
        val arm = badgeRadius * 0.4f
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, badgeCrossPaint)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, badgeCrossPaint)
    }

    private fun isOnDeleteBadge(rect: RectF, x: Float, y: Float): Boolean {
        val (cx, cy) = deleteBadgeCentre(rect)
        return abs(x - cx) <= badgeRadius && abs(y - cy) <= badgeRadius
    }

    private fun drawCornerHandles(canvas: Canvas, rect: RectF, skipTopRight: Boolean = false) {
        for (x in listOf(rect.left, rect.right)) {
            for (y in listOf(rect.top, rect.bottom)) {
                if (skipTopRight && x == rect.right && y == rect.top) continue
                canvas.drawCircle(x, y, handleRadius, handlePaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null || imageRect.isEmpty) return false
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount >= 2) {
            // A second finger turns the gesture into zoom/pan; abandon any edit it started as.
            beginPinch(event)
            parent?.requestDisallowInterceptTouchEvent(true)
            invalidate()
            return true
        }
        if (pinchActive) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (event.pointerCount >= 2) applyPinch(event)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pinchActive = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastTouchX = event.x
                lastTouchY = event.y
                dragMode = if (tool == Tool.CROP) beginCropDrag(event.x, event.y) else beginCensorDrag(event.x, event.y)
                invalidate()
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
                if (dragMode == DragMode.CENSOR_CREATE) {
                    val created = censors.getOrNull(selectedCensor)
                    if (created != null && (created.width() < minNormalisedSize || created.height() < minNormalisedSize)) {
                        // A tap rather than a drag: discard the degenerate rectangle and take it
                        // as the user asking to leave censor mode and get the crop handles back.
                        discardPendingCensor()
                        tool = Tool.CROP
                        onToolChanged?.invoke(Tool.CROP)
                    }
                }
                dragMode = DragMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun beginCropDrag(x: Float, y: Float): DragMode {
        val rect = crop.toScreen()
        val corner = nearestCorner(rect, x, y)
        if (corner != null) {
            dragCornerX = corner.first
            dragCornerY = corner.second
            return DragMode.CROP_RESIZE
        }
        // Touching an existing censor is a request to go back and adjust it. Crop handles win
        // when both are in range, so the crop is never impossible to grab.
        for (index in censors.indices.reversed()) {
            if (censors[index].toScreen().contains(x, y)) {
                // The tool setter clears the selection, so it has to be applied first.
                tool = Tool.CENSOR
                onToolChanged?.invoke(Tool.CENSOR)
                selectedCensor = index
                return DragMode.CENSOR_MOVE
            }
        }
        // Once zoomed, dragging navigates the image — otherwise a tall image zoomed in would only
        // be pannable with two fingers. At 1x there is nowhere to pan, so drag moves the crop box.
        if (zoom > 1f) return DragMode.PAN
        return if (rect.contains(x, y)) DragMode.CROP_MOVE else DragMode.NONE
    }

    private fun beginCensorDrag(x: Float, y: Float): DragMode {
        censors.getOrNull(selectedCensor)?.let { selected ->
            val screen = selected.toScreen()
            if (isOnDeleteBadge(screen, x, y)) {
                deleteSelectedCensor()
                return DragMode.NONE
            }
            val corner = nearestCorner(screen, x, y)
            if (corner != null) {
                dragCornerX = corner.first
                dragCornerY = corner.second
                return DragMode.CENSOR_RESIZE
            }
        }
        for (index in censors.indices.reversed()) {
            if (censors[index].toScreen().contains(x, y)) {
                selectedCensor = index
                return DragMode.CENSOR_MOVE
            }
        }
        if (!imageRect.contains(x, y)) return DragMode.NONE
        val nx = ((x - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
        val ny = ((y - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
        censors.add(RectF(nx, ny, nx, ny))
        selectedCensor = censors.lastIndex
        createAnchorX = nx
        createAnchorY = ny
        return DragMode.CENSOR_CREATE
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
            DragMode.PAN -> {
                panX += x - lastTouchX
                panY += y - lastTouchY
                clampPan()
            }
            DragMode.CROP_MOVE -> translateWithinBounds(crop, dx, dy)
            DragMode.CROP_RESIZE -> resizeCorner(crop, nx, ny)
            DragMode.CENSOR_MOVE -> censors.getOrNull(selectedCensor)?.let { translateWithinBounds(it, dx, dy) }
            DragMode.CENSOR_RESIZE -> censors.getOrNull(selectedCensor)?.let { resizeCorner(it, nx, ny) }
            DragMode.CENSOR_CREATE -> censors.getOrNull(selectedCensor)?.set(
                    min(createAnchorX, nx), min(createAnchorY, ny), max(createAnchorX, nx), max(createAnchorY, ny)
            )
            DragMode.NONE -> Unit
        }
    }

    private fun translateWithinBounds(rect: RectF, dx: Float, dy: Float) {
        val clampedDx = dx.coerceIn(-rect.left, 1f - rect.right)
        val clampedDy = dy.coerceIn(-rect.top, 1f - rect.bottom)
        rect.offset(clampedDx, clampedDy)
    }

    private fun resizeCorner(rect: RectF, nx: Float, ny: Float) {
        if (dragCornerX == 0) {
            rect.left = nx.coerceAtMost(rect.right - minNormalisedSize)
        } else {
            rect.right = nx.coerceAtLeast(rect.left + minNormalisedSize)
        }
        if (dragCornerY == 0) {
            rect.top = ny.coerceAtMost(rect.bottom - minNormalisedSize)
        } else {
            rect.bottom = ny.coerceAtLeast(rect.top + minNormalisedSize)
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
