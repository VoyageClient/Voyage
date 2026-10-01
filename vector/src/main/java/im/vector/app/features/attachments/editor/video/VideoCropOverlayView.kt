/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.view.animation.DecelerateInterpolator
import im.vector.app.features.attachments.ZoomPanGesture
import im.vector.app.features.attachments.editor.AngleSnap
import im.vector.app.features.attachments.editor.CropRatio
import im.vector.app.features.attachments.editor.QuarterStep
import im.vector.app.features.attachments.editor.RotatedCrop
import im.vector.app.features.attachments.editor.RotationSplit
import im.vector.app.features.attachments.editor.TwistTracker
import im.vector.app.features.attachments.editor.reduceRatio
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Below 1x, so the video can be shrunk to leave room around handles that sit on its edge. */
private const val MIN_ZOOM = 0.15f
private const val MAX_ZOOM = 20f

// Panning may run half a viewport past the content's edges, so a region can be put wherever the crop
// or the frame is instead of only where the content's own bounds allow.
private const val PAN_SLACK_FRACTION = 0.5f

private const val EDGE_INSET_FRACTION = 0.06f

private const val ROTATE_GRID_DIVISIONS = 6

private const val ANIMATION_MS = 280L

/**
 * The video editor's crop window: a freeform rectangle with corner handles, drawn over the
 * [android.view.TextureView] playing the clip. One geometry drives both, so the overlay hands back
 * the matrix the surface should be drawn with.
 *
 * The same shape and gestures as the image editor's `ImageEditorView`, minus the censor tool. The
 * clip turns underneath an upright crop, which may go anywhere on the turned picture (see
 * [RotatedCrop]); edits hand it back normalised against the frame at the quarter turn, which is what
 * the exporter's GL stage wants.
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

    var tiltDegrees = 0f
        private set

    /** The whole turn, quarter turns and tilt together, in [0, 360). */
    val rotationAngle get() = AngleSnap.normalise(rotationDegrees + tiltDegrees)

    /** Lets two fingers twist the angle as well as zoom. */
    var rotateMode = false
        set(value) {
            field = value
            invalidate()
        }

    /** Raised when a twist or a 90° step changes the angle, so the host can move its dial. */
    var onRotationChanged: ((Float) -> Unit)? = null

    /** Raised when a crop drag, a twist or a 90° step ends, for the host to record an undo step. */
    var onEditFinished: (() -> Unit)? = null

    private var lockedRatio: Float? = null

    /** Locked output ratio (width / height) for the crop window, or null to crop freely. */
    var aspectRatio: Float?
        get() = lockedRatio
        set(value) {
            lockedRatio = value
            applyRatioAroundCenter()
            rememberCrop()
            invalidate()
        }

    /** Pulls the dragged crop onto the frame's center lines, and the angle onto 45° steps. */
    var snapToCenter: Boolean = false
        set(value) {
            field = value
            if (!value) clearSnapGuides()
            invalidate()
        }

    private var videoWidth = 0
    private var videoHeight = 0

    /** Normalised against [imageRect], the upright box the turned picture fills. */
    private val crop = RectF(0f, 0f, 1f, 1f)

    /** The crop as last set, which a turn that forced [crop] smaller gives back on turning back. */
    private val wantedCrop = RectF()
    private var wantedCropKnown = false
    private var pendingFrameCrop: RectF? = null

    /** How much the turned picture has grown to keep covering the crop. */
    private var pictureScale = 1f

    /** The quarter turn the view is sized for; only the 90° steps change it, so the dial never zooms. */
    private var layoutQuarter = 0
    private var layoutZoom = 1f
    private var layoutAnimator: ValueAnimator? = null
    private var angleAnimator: ValueAnimator? = null

    /** Spins everything, surface included, through the last quarter turn so a 90° step reads as a turn. */
    private var spinDegrees = 0f
    private var spinAnimator: ValueAnimator? = null

    /** Turns only the picture, not the crop, while an undone tilt eases back in. */
    private var pictureTurn = 0f
    private var pictureTurnAnimator: ValueAnimator? = null

    /** Shape the clip will be sent at, when the sender chose one in the attachment preview. */
    var contentSizeOverride: Pair<Int, Int>? = null
        set(value) {
            if (field == value) return
            // Kept as the same share of the frame, now measured in the new shape.
            val frame = if (wantedCropKnown) frameCrop() else null
            field = value
            frame?.let { wantedCrop.set(RotatedCrop.fromFrameNormalised(it, pictureWidth, pictureHeight, rotationDegrees).scaledBy(pictureScale)) }
            fitCropToAngle()
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

    // Screen-based, not normalised: a crop on a small detail can legitimately be a few pixels of a
    // large frame, reached by zooming in.
    private val minCropScreenSize = dp(12f)
    private val minSizeCeiling = 0.05f

    private fun minNormalisedWidth() = minNormalised(imageRect.width())
    private fun minNormalisedHeight() = minNormalised(imageRect.height())

    private fun minNormalised(extent: Float) =
            if (extent <= 0f) minSizeCeiling else (minCropScreenSize / extent).coerceAtMost(minSizeCeiling)

    private enum class DragMode { NONE, PAN, CROP_MOVE, CROP_RESIZE }

    private var dragMode = DragMode.NONE
    private var dragCornerX = 0
    private var dragCornerY = 0
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val unsnappedCrop = RectF()
    private var snappedX = false
    private var snappedY = false

    private val gesture = ZoomPanGesture(MIN_ZOOM, MAX_ZOOM, panSlackFraction = PAN_SLACK_FRACTION, panWithPinch = true) { invalidate() }.apply {
        onDisallowIntercept = { parent?.requestDisallowInterceptTouchEvent(it) }
    }

    private val lastSurfaceMatrix = Matrix()

    private val twist = TwistTracker()
    private var twistNeedsBaseline = false
    private var unsnappedAngle = 0f

    /** The picture before any turn: the send shape when one was chosen, else the clip's own. */
    private val pictureWidth get() = (contentSizeOverride?.first ?: videoWidth).toFloat()
    private val pictureHeight get() = (contentSizeOverride?.second ?: videoHeight).toFloat()

    /** The picture as edited: turned, and grown by [pictureScale]. */
    private fun rotatedCrop(): RotatedCrop? =
            if (pictureWidth > 0f && pictureHeight > 0f) {
                RotatedCrop(pictureWidth * pictureScale, pictureHeight * pictureScale, rotationDegrees + tiltDegrees)
            } else {
                null
            }

    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        val frameCrop = pendingFrameCrop
        if (frameCrop != null || !wantedCropKnown) {
            wantedCrop.set(RotatedCrop.fromFrameNormalised(frameCrop ?: RectF(0f, 0f, 1f, 1f), pictureWidth, pictureHeight, rotationDegrees))
            wantedCropKnown = pictureWidth > 0f && pictureHeight > 0f
            pendingFrameCrop = if (wantedCropKnown) null else frameCrop
        }
        layoutQuarter = rotationDegrees
        fitCropToAngle()
        invalidate()
    }

    /** On to the next multiple of 90°. */
    fun rotateClockwise() = turnToQuarter(QuarterStep.next(rotationAngle), clockwise = true)

    /** Back to the previous multiple of 90°. */
    fun rotateCounterClockwise() = turnToQuarter(QuarterStep.previous(rotationAngle), clockwise = false)

    private fun turnToQuarter(target: Float, clockwise: Boolean) {
        angleAnimator?.cancel()
        val from = rotationAngle
        if (QuarterStep.isQuarter(from)) {
            // A whole quarter turn: the crop turns with the picture, and so does a chosen ratio, or
            // the crop would have to shrink to keep it.
            val turned = RotatedCrop.turnedClockwise(wantedCrop)
            wantedCrop.set(if (clockwise) turned else RotatedCrop.turnedClockwise(RotatedCrop.turnedClockwise(turned)))
            lockedRatio = lockedRatio?.let { 1f / it }
            setRotationAngle(target)
            relayoutTo(rotationDegrees)
            gesture.clampPan()
            spin(fromDegrees = if (clockwise) -90f else 90f)
            onRotationChanged?.invoke(rotationAngle)
            onEditFinished?.invoke()
            return
        }
        // Straightening a tilt: the crop stays where it is on screen, as with the dial.
        var delta = target - from
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        angleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                setRotationAngle(from + delta * it.animatedFraction)
                onRotationChanged?.invoke(rotationAngle)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    setRotationAngle(target)
                    onRotationChanged?.invoke(rotationAngle)
                    onEditFinished?.invoke()
                }
            })
            start()
        }
    }

    private fun spin(fromDegrees: Float) {
        spinAnimator?.cancel()
        spinAnimator = ValueAnimator.ofFloat(fromDegrees, 0f).apply {
            duration = ANIMATION_MS / 2
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                spinDegrees = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    spinDegrees = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    /** The picture turns under the crop, which keeps its place and size on screen. */
    fun setRotationAngle(degrees: Float) {
        val split = RotationSplit.of(degrees)
        rotationDegrees = split.quarterTurns
        tiltDegrees = split.tiltDegrees
        fitCropToAngle()
        invalidate()
    }

    /**
     * Keeps the crop its size on screen as the picture turns under it: where it would leave the
     * picture, the picture grows just enough to cover it again, and shrinks back once it need not.
     */
    private fun fitCropToAngle() {
        if (!wantedCropKnown || pictureWidth <= 0f || pictureHeight <= 0f) return
        pictureScale = RotatedCrop(pictureWidth, pictureHeight, rotationDegrees + tiltDegrees).requiredScale(wantedCrop)
        val geometry = rotatedCrop() ?: return
        crop.set(geometry.toBoundsNormalised(wantedCrop))
        applyRatioAroundCenter()
    }

    private fun rememberCrop() {
        val geometry = rotatedCrop() ?: return
        wantedCrop.set(geometry.fromBoundsNormalised(crop))
        wantedCropKnown = true
    }

    private fun fittedScale(quarter: Int): Float {
        val (frameWidth, frameHeight) = RotatedCrop.frameSize(pictureWidth, pictureHeight, quarter)
        // Inset the video so handles sitting on its edge aren't jammed against the screen edge.
        // Pinching out below 1x gives more room than this when a shot needs it.
        val pad = max(dp(28f), min(width, height) * EDGE_INSET_FRACTION)
        val availableWidth = width - pad * 2
        val availableHeight = height - pad * 2
        if (availableWidth <= 0 || availableHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) return 0f
        return min(availableWidth / frameWidth, availableHeight / frameHeight)
    }

    /** Eases into the size that fits [quarter], instead of jumping, once a turn has settled. */
    private fun relayoutTo(quarter: Int) {
        if (quarter == layoutQuarter) return
        val before = fittedScale(layoutQuarter) * layoutZoom
        val after = fittedScale(quarter)
        layoutQuarter = quarter
        layoutAnimator?.cancel()
        if (before <= 0f || after <= 0f) {
            layoutZoom = 1f
            return
        }
        layoutAnimator = ValueAnimator.ofFloat(before / after, 1f).apply {
            duration = ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                layoutZoom = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    data class State(
            val rotationDegrees: Int,
            val tiltDegrees: Float,
            val crop: RectF,
            val aspectRatio: Float?,
            val pictureScale: Float,
    )

    fun currentState() = State(rotationDegrees, tiltDegrees, frameCrop(), lockedRatio, pictureScale)

    /** Restores an undo step; [animate] eases a changed angle in rather than jumping to it. */
    fun restoreState(state: State, animate: Boolean = true) {
        val previousAngle = rotationAngle
        lockedRatio = state.aspectRatio
        restoreEdits(state.rotationDegrees, state.crop, state.tiltDegrees, state.pictureScale)
        gesture.clampPan()
        if (animate) animateTurnFrom(previousAngle)
    }

    /**
     * Whole quarter turns spin everything, crop included, as the 90° buttons do; a tilt turns only
     * the picture, as the dial does, since the crop did not move with it.
     */
    private fun animateTurnFrom(previousAngle: Float) {
        var delta = previousAngle - rotationAngle
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        if (abs(delta) < 0.01f) return
        if (QuarterStep.isQuarter(delta)) {
            spin(fromDegrees = delta)
            return
        }
        pictureTurnAnimator?.cancel()
        pictureTurnAnimator = ValueAnimator.ofFloat(delta, 0f).apply {
            duration = ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pictureTurn = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun resetEdits() {
        angleAnimator?.cancel()
        rotationDegrees = 0
        tiltDegrees = 0f
        pictureScale = 1f
        relayoutTo(0)
        crop.set(0f, 0f, 1f, 1f)
        gesture.reset()
        applyRatioAroundCenter()
        rememberCrop()
        clearSnapGuides()
        invalidate()
    }

    /** The frame's own ratio, reduced, to offer as the starting point for a custom one. */
    fun displayedAspectRatio(): Pair<Int, Int>? {
        if (pictureWidth <= 0f || pictureHeight <= 0f) return null
        val (width, height) = RotatedCrop.frameSize(pictureWidth, pictureHeight, rotationDegrees)
        return reduceRatio(width.toInt(), height.toInt())
    }

    /** A ratio in output pixels, in units of [crop], which is normalised against the turned picture's box. */
    private fun normalisedRatio(): Float? {
        val geometry = rotatedCrop() ?: return null
        return CropRatio.normalise(aspectRatio, geometry.boundsWidth, geometry.boundsHeight)
    }

    private fun applyRatioAroundCenter() {
        CropRatio.fitAroundCenter(crop, normalisedRatio() ?: return)
    }

    /** Normalised against the frame at the quarter turn; may run past 0..1 when tilted. */
    private fun frameCrop(): RectF {
        pendingFrameCrop?.let { return RectF(it) }
        val geometry = rotatedCrop() ?: return RectF(crop)
        // What the crop covers of the picture at its own size, not grown.
        val onPicture = geometry.fromBoundsNormalised(crop).scaledBy(1f / pictureScale)
        return RotatedCrop.toFrameNormalised(onPicture, pictureWidth, pictureHeight, rotationDegrees)
    }

    private fun RectF.scaledBy(factor: Float) = RectF(left * factor, top * factor, right * factor, bottom * factor)

    /** The kept region of the displayed frame, or null when the whole of it is kept. */
    fun currentCrop(): RectF? {
        val frame = frameCrop()
        val whole = tiltDegrees == 0f && frame.left <= WHOLE_TOLERANCE && frame.top <= WHOLE_TOLERANCE &&
                frame.right >= 1f - WHOLE_TOLERANCE && frame.bottom >= 1f - WHOLE_TOLERANCE
        return if (whole) null else frame
    }

    /** [scale] is how far the picture had grown, so the crop comes back at the size it was shown at. */
    fun restoreEdits(rotation: Int, savedCrop: RectF?, tilt: Float = 0f, scale: Float = 1f) {
        angleAnimator?.cancel()
        val split = RotationSplit.of(rotation + tilt)
        rotationDegrees = split.quarterTurns
        tiltDegrees = split.tiltDegrees
        pictureScale = scale
        val frameCrop = savedCrop ?: RectF(0f, 0f, 1f, 1f)
        val geometry = rotatedCrop()
        if (geometry == null) {
            pendingFrameCrop = RectF(frameCrop)
        } else {
            wantedCrop.set(RotatedCrop.fromFrameNormalised(frameCrop, pictureWidth, pictureHeight, rotationDegrees).scaledBy(scale))
            wantedCropKnown = true
            crop.set(geometry.toBoundsNormalised(wantedCrop))
            relayoutTo(rotationDegrees)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!computeGeometry()) return
        // Turned with the surface, so the crop stays locked to the picture mid-spin.
        if (spinDegrees != 0f) canvas.rotate(spinDegrees, width / 2f, height / 2f)

        val cropScreen = crop.toScreen()
        drawDimOutside(canvas, cropScreen)
        canvas.drawRect(cropScreen, borderPaint)
        drawGrid(canvas, cropScreen, if (rotateMode) ROTATE_GRID_DIVISIONS else 3)
        drawCornerHandles(canvas, cropScreen)
        drawSnapGuides(canvas)
    }

    private fun drawSnapGuides(canvas: Canvas) {
        if (dragMode == DragMode.NONE) return
        if (snappedX) canvas.drawLine(imageRect.centerX(), imageRect.top, imageRect.centerX(), imageRect.bottom, snapGuidePaint)
        if (snappedY) canvas.drawLine(imageRect.left, imageRect.centerY(), imageRect.right, imageRect.centerY(), snapGuidePaint)
    }

    private fun computeGeometry(): Boolean {
        val geometry = rotatedCrop() ?: return false
        // Sized for the settled orientation, not the live angle, so turning never zooms the picture.
        val fittedScale = fittedScale(layoutQuarter) * layoutZoom
        if (fittedScale <= 0f) return false
        gesture.contentWidth = geometry.boundsWidth * fittedScale
        gesture.contentHeight = geometry.boundsHeight * fittedScale
        gesture.viewportWidth = width.toFloat()
        gesture.viewportHeight = height.toFloat()

        val scale = fittedScale * gesture.zoom
        val drawnWidth = geometry.boundsWidth * scale
        val drawnHeight = geometry.boundsHeight * scale
        val centreX = width / 2f + gesture.panX
        val centreY = height / 2f + gesture.panY
        imageRect.set(centreX - drawnWidth / 2f, centreY - drawnHeight / 2f, centreX + drawnWidth / 2f, centreY + drawnHeight / 2f)

        // The surface is stretched to fill the view, so this scales against the view, not the frame,
        // down to the unturned picture, which is then turned about the view's center.
        surfaceMatrix.reset()
        val pictureOnScreen = scale * pictureScale
        surfaceMatrix.setScale(pictureWidth * pictureOnScreen / width, pictureHeight * pictureOnScreen / height, width / 2f, height / 2f)
        surfaceMatrix.postRotate(rotationDegrees + tiltDegrees + pictureTurn, width / 2f, height / 2f)
        surfaceMatrix.postTranslate(gesture.panX, gesture.panY)
        if (spinDegrees != 0f) surfaceMatrix.postRotate(spinDegrees, width / 2f, height / 2f)
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

    private fun drawGrid(canvas: Canvas, rect: RectF, divisions: Int) {
        val stepWidth = rect.width() / divisions
        val stepHeight = rect.height() / divisions
        for (index in 1 until divisions) {
            canvas.drawLine(rect.left + stepWidth * index, rect.top, rect.left + stepWidth * index, rect.bottom, gridPaint)
            canvas.drawLine(rect.left, rect.top + stepHeight * index, rect.right, rect.top + stepHeight * index, gridPaint)
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
            if (rotateMode) {
                angleAnimator?.cancel()
                twist.begin(event)
                twistNeedsBaseline = false
                unsnappedAngle = rotationAngle
            }
            dragMode = DragMode.NONE
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (gesture.isPinching) {
            if (rotateMode) trackTwist(event)
            val handled = gesture.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                onEditFinished?.invoke()
            }
            return handled
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
                val edited = dragMode == DragMode.CROP_MOVE || dragMode == DragMode.CROP_RESIZE
                if (edited) rememberCrop()
                dragMode = DragMode.NONE
                clearSnapGuides()
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                if (edited) onEditFinished?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun trackTwist(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_UP -> twistNeedsBaseline = true
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount < 2) return
                if (twistNeedsBaseline) {
                    twist.begin(event)
                    twistNeedsBaseline = false
                    return
                }
                unsnappedAngle += twist.delta(event)
                val next = AngleSnap.snap(unsnappedAngle, snapToCenter)
                if (next != rotationAngle) {
                    setRotationAngle(next)
                    onRotationChanged?.invoke(next)
                }
            }
        }
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
            DragMode.CROP_MOVE -> {
                val before = RectF(crop)
                moveWithSnap(dx, dy)
                // Held at the picture's edge, the finger's own position stops counting until it returns.
                if (keepCropOnPicture(before, slide = true)) unsnappedCrop.set(crop)
            }
            DragMode.CROP_RESIZE -> {
                val before = RectF(crop)
                val nx = snapX(normalisedX(x))
                val ny = snapY(normalisedY(y))
                val k = normalisedRatio()
                if (k != null) {
                    CropRatio.resize(crop, k, nx, ny, dragCornerX, dragCornerY, minNormalisedWidth(), minNormalisedHeight())
                } else {
                    resizeCorner(nx, ny)
                }
                // Sliding along one axis would change a locked shape.
                keepCropOnPicture(before, slide = k == null)
            }
            DragMode.NONE -> Unit
        }
    }

    /**
     * Pulls [crop] back from wherever it left the turned picture, toward [before], which was on it.
     * With [slide], whichever axis still has room keeps going, so the box glides along the edge.
     * Returns whether it had to.
     */
    private fun keepCropOnPicture(before: RectF, slide: Boolean): Boolean {
        val geometry = rotatedCrop() ?: return false
        val wanted = geometry.fromBoundsNormalised(crop)
        if (geometry.fits(wanted)) return false
        var reached = geometry.reachable(geometry.fromBoundsNormalised(before), wanted)
        if (slide) {
            reached = geometry.reachable(reached, RectF(wanted.left, reached.top, wanted.right, reached.bottom))
            reached = geometry.reachable(reached, RectF(reached.left, wanted.top, reached.right, wanted.bottom))
        }
        crop.set(geometry.toBoundsNormalised(reached))
        return true
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
            0 -> crop.left = nx.coerceAtMost(crop.right - minNormalisedWidth())
            1 -> crop.right = nx.coerceAtLeast(crop.left + minNormalisedWidth())
        }
        when (dragCornerY) {
            0 -> crop.top = ny.coerceAtMost(crop.bottom - minNormalisedHeight())
            1 -> crop.bottom = ny.coerceAtLeast(crop.top + minNormalisedHeight())
        }
    }

    private fun RectF.toScreen() = RectF(
            imageRect.left + left * imageRect.width(),
            imageRect.top + top * imageRect.height(),
            imageRect.left + right * imageRect.width(),
            imageRect.top + bottom * imageRect.height()
    )

    private fun dp(value: Float) = value * resources.displayMetrics.density

    companion object {
        private const val WHOLE_TOLERANCE = 0.001f
    }
}
