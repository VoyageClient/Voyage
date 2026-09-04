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
import im.vector.app.features.attachments.ZoomPanGesture
import im.vector.app.features.attachments.editor.CropRatio
import im.vector.app.features.attachments.editor.reduceRatio
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val ROTATION_ANIMATION_MS = 140L

/** Below 1x, so the image can be shrunk to leave room around handles that sit on its edge. */
private const val MIN_ZOOM = 0.15f

/** Deliberately deeper than the media viewer's 6x, so small details can be censored precisely. */
private const val MAX_ZOOM = 20f

private const val EDGE_INSET_FRACTION = 0.06f

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

    /** Locked output ratio (width / height) for the crop window, or null to crop freely. */
    var cropAspectRatio: Float? = null
        set(value) {
            field = value
            applyRatioAroundCenter(crop, value)
            invalidate()
        }

    /** Pulls a dragged crop or censor onto the image's center lines when it comes close. */
    var snapToCenter: Boolean = false
        set(value) {
            field = value
            if (!value) clearSnapGuides()
            invalidate()
        }

    private var bitmap: Bitmap? = null
    private var userRotation = 0
    private val crop = RectF(0f, 0f, 1f, 1f)
    private val censors = mutableListOf<CensorBox>()
    private var selectedCensor = -1

    /** A censor keeps its own locked ratio, so the aspect tool can act on one box at a time. */
    private class CensorBox(val rect: RectF, var aspectRatio: Float? = null)

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
    private val edgeHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
    }
    private val edgeHandleLength = dp(16f)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
    }

    private val snapGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }

    private val badgeBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    private val badgeCrossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    private val snapDistance = dp(14f)
    private val handleRadius = dp(8f)
    private val badgeRadius = dp(12f)
    private val touchSlop = max(dp(24f), ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private val tapSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // Minimums are screen-based, not normalised: a crop or censor over a small detail can
    // legitimately be a few pixels of a large image, reached by zooming in.
    private val minCropScreenSize = dp(12f)
    private val minCensorScreenSize = dp(4f)
    private val minSizeCeiling = 0.05f

    private fun minNormalised(screenSize: Float, extent: Float) =
            if (extent <= 0f) minSizeCeiling else (screenSize / extent).coerceAtMost(minSizeCeiling)

    private fun minCropNormalisedWidth() = minNormalised(minCropScreenSize, imageRect.width())
    private fun minCropNormalisedHeight() = minNormalised(minCropScreenSize, imageRect.height())
    private fun minCensorNormalisedWidth() = minNormalised(minCensorScreenSize, imageRect.width())
    private fun minCensorNormalisedHeight() = minNormalised(minCensorScreenSize, imageRect.height())

    private var dragMode = DragMode.NONE
    private var dragCornerX = 0
    private var dragCornerY = 0
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var createAnchorX = 0f
    private var createAnchorY = 0f
    private var animatedRotation = 0f
    private var rotationAnimator: ValueAnimator? = null
    private val unsnappedRect = RectF()
    private var snappedX = false
    private var snappedY = false

    private val gesture = ZoomPanGesture(MIN_ZOOM, MAX_ZOOM) { invalidate() }.apply {
        onDisallowIntercept = { parent?.requestDisallowInterceptTouchEvent(it) }
    }

    private fun beginPinch(event: MotionEvent) {
        // The first finger already staked out a zero-size censor; abandoning the drag here would
        // otherwise strand it in the list, invisible but enough to count as an edit.
        if (dragMode == DragMode.CENSOR_CREATE) discardPendingCensor()
        clearSnapGuides()
        // A pinch is navigation: leave censor mode so the next one-finger drag pans, not paints.
        if (tool == Tool.CENSOR) {
            tool = Tool.CROP
            onToolChanged?.invoke(Tool.CROP)
        }
        dragMode = DragMode.NONE
        gesture.beginPinch(event)
    }

    private fun discardPendingCensor() {
        if (selectedCensor !in censors.indices) return
        censors.removeAt(selectedCensor)
        selectedCensor = -1
    }

    private enum class DragMode { NONE, PAN, CROP_MOVE, CROP_RESIZE, CENSOR_MOVE, CENSOR_RESIZE, CENSOR_CREATE }

    fun setBitmap(value: Bitmap) {
        bitmap = value
        applyRatioAroundCenter(crop, cropAspectRatio)
        requestLayout()
        invalidate()
    }

    fun rotateClockwise() {
        userRotation = (userRotation + 90) % 360
        rotateNormalised(crop)
        censors.forEach { rotateNormalised(it.rect) }
        // The turn swapped every box's own ratio, so a locked one has to be re-derived for the
        // new orientation.
        applyRatioAroundCenter(crop, cropAspectRatio)
        censors.forEach { applyRatioAroundCenter(it.rect, it.aspectRatio) }
        gesture.clampPan()
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
        gesture.reset()
        applyRatioAroundCenter(crop, cropAspectRatio)
        clearSnapGuides()
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
            censors = censors.map { RectF(it.rect) }
    )

    fun restoreEdits(edits: ImageEditorEdits) {
        userRotation = edits.userRotation
        crop.set(edits.crop)
        censors.clear()
        censors.addAll(edits.censors.map { CensorBox(RectF(it)) })
        selectedCensor = -1
        invalidate()
    }

    /** A 90 degree clockwise turn maps (x, y) to (1 - y, x). */
    private fun rotateNormalised(rect: RectF) {
        rect.set(1f - rect.bottom, rect.left, 1f - rect.top, rect.right)
    }

    /** The size the image is shown at, which the normalised rectangles are measured against. */
    private fun displayedSize(): Pair<Float, Float>? {
        val bmp = bitmap ?: return null
        val sideways = userRotation % 180 != 0
        val width = (if (sideways) bmp.height else bmp.width).toFloat()
        val height = (if (sideways) bmp.width else bmp.height).toFloat()
        return if (width > 0f && height > 0f) width to height else null
    }

    /** The image's own ratio, reduced, to offer as the starting point for a custom one. */
    fun displayedAspectRatio(): Pair<Int, Int>? {
        val (width, height) = displayedSize() ?: return null
        return reduceRatio(width.toInt(), height.toInt())
    }

    private fun normalisedRatio(ratio: Float?): Float? {
        val (width, height) = displayedSize() ?: return null
        return CropRatio.normalise(ratio, width, height)
    }

    private fun applyRatioAroundCenter(rect: RectF, ratio: Float?) {
        CropRatio.fitAroundCenter(rect, normalisedRatio(ratio) ?: return)
    }

    /** True when the aspect tool would act on a censor rather than on the crop window. */
    fun isCensorSelected() = tool == Tool.CENSOR && selectedCensor in censors.indices

    /** The locked ratio of whatever the aspect tool acts on right now. */
    fun selectionAspectRatio(): Float? =
            if (isCensorSelected()) censors[selectedCensor].aspectRatio else cropAspectRatio

    fun applySelectionAspectRatio(ratio: Float?) {
        if (isCensorSelected()) {
            censors[selectedCensor].let {
                it.aspectRatio = ratio
                applyRatioAroundCenter(it.rect, ratio)
            }
        } else {
            cropAspectRatio = ratio
        }
        invalidate()
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

        censors.forEach { canvas.drawRect(it.rect.toScreen(), censorPaint) }

        val cropScreen = crop.toScreen()
        drawDimOutside(canvas, cropScreen)

        if (tool == Tool.CROP) {
            canvas.drawRect(cropScreen, borderPaint)
            drawThirds(canvas, cropScreen)
            drawCornerHandles(canvas, cropScreen)
        } else {
            canvas.drawRect(cropScreen, gridPaint)
            censors.getOrNull(selectedCensor)?.let { selected ->
                val r = selected.rect.toScreen()
                canvas.drawRect(r, selectionPaint)
                drawCornerHandles(canvas, r, skipTopRight = true)
                drawDeleteBadge(canvas, r)
            }
        }

        drawSnapGuides(canvas)

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
        gesture.contentWidth = srcW * fittedScale
        gesture.contentHeight = srcH * fittedScale
        gesture.viewportWidth = width.toFloat()
        gesture.viewportHeight = height.toFloat()
        val scale = fittedScale * gesture.zoom
        val drawnW = srcW * scale
        val drawnH = srcH * scale
        val cx = width / 2f + gesture.panX
        val cy = height / 2f + gesture.panY
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

    private fun drawSnapGuides(canvas: Canvas) {
        if (dragMode == DragMode.NONE) return
        if (snappedX) canvas.drawLine(imageRect.centerX(), imageRect.top, imageRect.centerX(), imageRect.bottom, snapGuidePaint)
        if (snappedY) canvas.drawLine(imageRect.left, imageRect.centerY(), imageRect.right, imageRect.centerY(), snapGuidePaint)
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null || imageRect.isEmpty) return false
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount >= 2) {
            // A second finger turns the gesture into zoom/pan; abandon any edit it started as.
            beginPinch(event)
            parent?.requestDisallowInterceptTouchEvent(true)
            invalidate()
            return true
        }
        if (gesture.isPinching) return gesture.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                clearSnapGuides()
                lastTouchX = event.x
                lastTouchY = event.y
                dragMode = if (tool == Tool.CROP) beginCropDrag(event.x, event.y) else beginCensorDrag(event.x, event.y)
                when (dragMode) {
                    DragMode.CROP_MOVE -> unsnappedRect.set(crop)
                    DragMode.CENSOR_MOVE -> censors.getOrNull(selectedCensor)?.let { unsnappedRect.set(it.rect) }
                    else -> Unit
                }
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
                    val created = censors.getOrNull(selectedCensor)?.rect?.toScreen()
                    if (created != null && created.width() < tapSlop && created.height() < tapSlop) {
                        // A tap rather than a drag: discard it and leave censor mode. Judged on
                        // screen distance, so a deliberately drawn small censor survives.
                        discardPendingCensor()
                        tool = Tool.CROP
                        onToolChanged?.invoke(Tool.CROP)
                    }
                }
                dragMode = DragMode.NONE
                clearSnapGuides()
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun beginCropDrag(x: Float, y: Float): DragMode {
        val rect = crop.toScreen()
        val handle = nearestHandle(rect, x, y)
        if (handle != null) {
            dragCornerX = handle.first
            dragCornerY = handle.second
            return DragMode.CROP_RESIZE
        }
        // Touching an existing censor is a request to go back and adjust it. Crop handles win
        // when both are in range, so the crop is never impossible to grab.
        for (index in censors.indices.reversed()) {
            if (censors[index].rect.toScreen().contains(x, y)) {
                // The tool setter clears the selection, so it has to be applied first.
                tool = Tool.CENSOR
                onToolChanged?.invoke(Tool.CENSOR)
                selectedCensor = index
                return DragMode.CENSOR_MOVE
            }
        }
        // Inside the box moves it; anywhere else navigates the image, so a tall image zoomed in is
        // not pannable only with two fingers. A box filling the frame has nowhere to move, so it
        // gives the drag up rather than swallowing it.
        if (rect.contains(x, y) && cropCanMove()) return DragMode.CROP_MOVE
        return if (gesture.zoom > 1f) DragMode.PAN else DragMode.NONE
    }

    private fun cropCanMove() = crop.width() < 0.999f || crop.height() < 0.999f

    private fun beginCensorDrag(x: Float, y: Float): DragMode {
        censors.getOrNull(selectedCensor)?.let { selected ->
            val screen = selected.rect.toScreen()
            if (isOnDeleteBadge(screen, x, y)) {
                deleteSelectedCensor()
                return DragMode.NONE
            }
            val handle = nearestHandle(screen, x, y)
            if (handle != null) {
                dragCornerX = handle.first
                dragCornerY = handle.second
                return DragMode.CENSOR_RESIZE
            }
        }
        for (index in censors.indices.reversed()) {
            if (censors[index].rect.toScreen().contains(x, y)) {
                selectedCensor = index
                return DragMode.CENSOR_MOVE
            }
        }
        if (!imageRect.contains(x, y)) return DragMode.NONE
        val nx = snapX(normalisedX(x))
        val ny = snapY(normalisedY(y))
        censors.add(CensorBox(RectF(nx, ny, nx, ny)))
        selectedCensor = censors.lastIndex
        createAnchorX = nx
        createAnchorY = ny
        return DragMode.CENSOR_CREATE
    }

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
            DragMode.CROP_MOVE -> moveWithSnap(crop, dx, dy)
            DragMode.CROP_RESIZE -> {
                val nx = snapX(normalisedX(x))
                val ny = snapY(normalisedY(y))
                val k = normalisedRatio(cropAspectRatio)
                if (k != null) {
                    CropRatio.resize(crop, k, nx, ny, dragCornerX, dragCornerY, minCropNormalisedWidth(), minCropNormalisedHeight())
                } else {
                    resizeCorner(crop, nx, ny, minCropNormalisedWidth(), minCropNormalisedHeight())
                }
            }
            DragMode.CENSOR_MOVE -> censors.getOrNull(selectedCensor)?.let { moveWithSnap(it.rect, dx, dy) }
            DragMode.CENSOR_RESIZE -> censors.getOrNull(selectedCensor)?.let {
                val nx = snapX(normalisedX(x))
                val ny = snapY(normalisedY(y))
                val k = normalisedRatio(it.aspectRatio)
                if (k != null) {
                    CropRatio.resize(it.rect, k, nx, ny, dragCornerX, dragCornerY, minCensorNormalisedWidth(), minCensorNormalisedHeight())
                } else {
                    resizeCorner(it.rect, nx, ny, minCensorNormalisedWidth(), minCensorNormalisedHeight())
                }
            }
            DragMode.CENSOR_CREATE -> {
                val nx = snapX(normalisedX(x))
                val ny = snapY(normalisedY(y))
                censors.getOrNull(selectedCensor)?.rect?.set(
                        min(createAnchorX, nx), min(createAnchorY, ny), max(createAnchorX, nx), max(createAnchorY, ny)
                )
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
     * The finger moves [unsnappedRect]; the visible rect only follows it onto a center line while it
     * is close to one. Snapping the visible rect instead would re-snap it on every event of a slow
     * drag, since each event's own delta stays inside the snap distance, and it could never be pulled off.
     */
    private fun moveWithSnap(rect: RectF, dx: Float, dy: Float) {
        translateWithinBounds(unsnappedRect, dx, dy)
        rect.set(unsnappedRect)
        if (!snapToCenter) return
        if (snapX(rect.centerX()) == 0.5f) rect.offset(0.5f - rect.centerX(), 0f)
        if (snapY(rect.centerY()) == 0.5f) rect.offset(0f, 0.5f - rect.centerY())
    }

    private fun translateWithinBounds(rect: RectF, dx: Float, dy: Float) {
        val clampedDx = dx.coerceIn(-rect.left, 1f - rect.right)
        val clampedDy = dy.coerceIn(-rect.top, 1f - rect.bottom)
        rect.offset(clampedDx, clampedDy)
    }

    private fun resizeCorner(
            rect: RectF,
            nx: Float,
            ny: Float,
            minWidth: Float,
            minHeight: Float,
    ) {
        when (dragCornerX) {
            0 -> rect.left = nx.coerceAtMost(rect.right - minWidth)
            1 -> rect.right = nx.coerceAtLeast(rect.left + minWidth)
        }
        when (dragCornerY) {
            0 -> rect.top = ny.coerceAtMost(rect.bottom - minHeight)
            1 -> rect.bottom = ny.coerceAtLeast(rect.top + minHeight)
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
