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
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import im.vector.app.features.attachments.ZoomPanGesture
import im.vector.app.features.attachments.editor.AngleSnap
import im.vector.app.features.attachments.editor.CropRatio
import im.vector.app.features.attachments.editor.EditHistory
import im.vector.app.features.attachments.editor.EditorColorBar
import im.vector.app.features.attachments.editor.QuarterStep
import im.vector.app.features.attachments.editor.RotatedCrop
import im.vector.app.features.attachments.editor.RotationSplit
import im.vector.app.features.attachments.editor.TwistTracker
import im.vector.app.features.attachments.editor.reduceRatio
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val ROTATION_ANIMATION_MS = 140L

/** Below 1x, so the image can be shrunk to leave room around handles that sit on its edge. */
private const val MIN_ZOOM = 0.15f

/** Deliberately deeper than the media viewer's 6x, so small details can be censored precisely. */
private const val MAX_ZOOM = 20f

// Panning may run half a viewport past the content's edges, so a region can be put wherever the crop
// or the frame is instead of only where the content's own bounds allow.
private const val PAN_SLACK_FRACTION = 0.5f

private const val EDGE_INSET_FRACTION = 0.06f

private const val ROTATE_GRID_DIVISIONS = 6

/**
 * Renders the image being edited plus its crop window, censors and brush strokes, and turns touches
 * into edits.
 *
 * Two spaces are in play. The crop stays upright on screen while the image turns underneath it, and
 * is normalised against the box the turned image fills ([imageRect]; see [RotatedCrop]). Censors and
 * strokes are normalised against the unrotated image, so they stay on what they cover; they are
 * edited in an unrotated screen space ([sourceRect]) that touches are turned back into.
 */
class ImageEditorView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Tool { CROP, CENSOR, DRAW, ROTATE }

    var tool: Tool = Tool.CROP
        set(value) {
            field = value
            selectCensor(-1)
            abandonStroke()
            invalidate()
        }

    /** Raised when the view changes tool on its own, so the host can re-style its buttons. */
    var onToolChanged: ((Tool) -> Unit)? = null

    /** Raised when a twist changes the angle, so the host can move its dial. */
    var onRotationChanged: ((Float) -> Unit)? = null

    /** Raised whenever undo or redo may have become (un)available, or state was restored from them. */
    var onHistoryChanged: (() -> Unit)? = null

    /** Raised with the selected censor's color, or null when none is selected. */
    var onCensorSelectionChanged: ((Int?) -> Unit)? = null

    private var lockedCropRatio: Float? = null

    /** Locked output ratio (width / height) for the crop window, or null to crop freely. */
    var cropAspectRatio: Float?
        get() = lockedCropRatio
        set(value) {
            lockedCropRatio = value
            applyRatioAroundCenter(crop, value)
            rememberCrop()
            invalidate()
        }

    /** Pulls a dragged crop or censor onto the image's center lines, and a tilt onto 45° steps. */
    var snapToCenter: Boolean = false
        set(value) {
            field = value
            if (!value) clearSnapGuides()
            invalidate()
        }

    var brushColor: Int = EditorColorBar.RED

    /** On-screen brush diameter; zooming in therefore draws finer. */
    var brushSizeDp: Float = 6f

    /** Color for new censors. */
    var censorColor: Int = Color.BLACK

    var tiltDegrees = 0f
        private set

    /** The whole turn, quarter turns and tilt together, in [0, 360). */
    val rotationAngle get() = AngleSnap.normalise(userRotation + tiltDegrees)

    private var bitmap: Bitmap? = null
    private var animatedDrawable: Drawable? = null
    private val imageWidth get() = animatedDrawable?.intrinsicWidth ?: bitmap?.width ?: 0
    private val imageHeight get() = animatedDrawable?.intrinsicHeight ?: bitmap?.height ?: 0
    private var userRotation = 0

    /** Normalised against [imageRect], the upright box the turned image fills. */
    private val crop = RectF(0f, 0f, 1f, 1f)

    /**
     * The crop as the user last set it, in [RotatedCrop] pixels. Turning the image may force [crop]
     * smaller to stay on it; turning back gives this back rather than the shrunken one.
     */
    private val wantedCrop = RectF()
    private var wantedCropKnown = false

    /** Edits restored before the image loaded, whose crop needs its size to be resolved. */
    private var pendingFrameCrop: RectF? = null

    /** How much the turned image has grown to keep covering the crop. */
    private var pictureScale = 1f

    /** False when the caller fixed the output shape, which a quarter turn must not swap. */
    var ratioTurnsWithImage = true

    /** The quarter turn the view is sized for; only the 90° steps change it, so the dial never zooms. */
    private var layoutQuarter = 0
    private var layoutZoom = 1f
    private var layoutAnimator: ValueAnimator? = null
    private var angleAnimator: ValueAnimator? = null

    /** Turns only the image, not the crop, while an undone tilt eases back in. */
    private var pictureTurn = 0f
    private var pictureTurnAnimator: ValueAnimator? = null
    private val censors = mutableListOf<CensorBox>()
    private var selectedCensor = -1
    private val strokes = mutableListOf<BrushStroke>()
    private val strokePaths = mutableListOf<Path>()
    private var strokePathsStale = true

    /**
     * A censor keeps its own locked ratio, so the aspect tool can act on one box at a time. The ratio
     * is in unrotated-image terms, like the box.
     */
    private class CensorBox(val rect: RectF, var aspectRatio: Float? = null, var color: Int = Color.BLACK)

    /** The box the turned image fills on screen, which the crop is normalised against. */
    private val imageRect = RectF()

    /** The unrotated image on screen, around the frame's center, which annotations are edited in. */
    private val sourceRect = RectF()

    /** Unrotated image pixels to screen. */
    private val drawMatrix = Matrix()
    private val toSourceSpace = Matrix()
    private val touchPoint = FloatArray(2)

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val censorPaint = Paint()
    private val strokePaint = ImageAnnotationPainter.strokePaint()
    private val dimPaint = Paint().apply { color = 0xB0000000.toInt() }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val fineGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x50FFFFFF
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
    private val strokeStep = dp(1.5f)

    // Minimums are screen-based, not normalised: a crop or censor over a small detail can
    // legitimately be a few pixels of a large image, reached by zooming in.
    private val minCropScreenSize = dp(12f)
    private val minCensorScreenSize = dp(4f)
    private val minSizeCeiling = 0.05f

    private fun minNormalised(screenSize: Float, extent: Float) =
            if (extent <= 0f) minSizeCeiling else (screenSize / extent).coerceAtMost(minSizeCeiling)

    private fun minCropNormalisedWidth() = minNormalised(minCropScreenSize, imageRect.width())
    private fun minCropNormalisedHeight() = minNormalised(minCropScreenSize, imageRect.height())
    private fun minCensorNormalisedWidth() = minNormalised(minCensorScreenSize, sourceRect.width())
    private fun minCensorNormalisedHeight() = minNormalised(minCensorScreenSize, sourceRect.height())

    private var dragMode = DragMode.NONE
    private var dragCornerX = 0
    private var dragCornerY = 0
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastSourceX = 0f
    private var lastSourceY = 0f
    private var createAnchorX = 0f
    private var createAnchorY = 0f
    private var animatedRotation = 0f
    private var rotationAnimator: ValueAnimator? = null
    private val unsnappedRect = RectF()
    private var snappedX = false
    private var snappedY = false

    /** Whether the drag being snapped is a censor's, measured against [sourceRect] not [imageRect]. */
    private var snapInSource = false

    private var liveStroke: FloatArray = FloatArray(64)
    private var liveStrokeSize = 0
    private var liveStrokeWidth = 0f
    private var lastStrokeScreenX = 0f
    private var lastStrokeScreenY = 0f

    private val twist = TwistTracker()
    private var twistNeedsBaseline = false
    private var unsnappedAngle = 0f

    private val gesture = ZoomPanGesture(MIN_ZOOM, MAX_ZOOM, panSlackFraction = PAN_SLACK_FRACTION, panWithPinch = true) { invalidate() }.apply {
        onDisallowIntercept = { parent?.requestDisallowInterceptTouchEvent(it) }
    }

    private data class Snapshot(
            val edits: ImageEditorEdits,
            val cropRatio: Float?,
            val censorRatios: List<Float?>,
            val pictureScale: Float,
    )

    private var history = EditHistory<Snapshot>()
    private var historyStarted = false
    private var resumableHistory: EditHistory<*>? = null

    val canUndo get() = history.canUndo
    val canRedo get() = history.canRedo

    private fun beginPinch(event: MotionEvent) {
        // The first finger already staked out a zero-size censor; abandoning the drag here would
        // otherwise strand it in the list, invisible but enough to count as an edit.
        if (dragMode == DragMode.CENSOR_CREATE) discardPendingCensor()
        abandonStroke()
        clearSnapGuides()
        // A pinch is navigation: leave censor mode so the next one-finger drag pans, not paints.
        if (tool == Tool.CENSOR) {
            tool = Tool.CROP
            onToolChanged?.invoke(Tool.CROP)
        }
        if (tool == Tool.ROTATE) {
            twist.begin(event)
            twistNeedsBaseline = false
            unsnappedAngle = rotationAngle
        }
        dragMode = DragMode.NONE
        gesture.beginPinch(event)
    }

    private fun discardPendingCensor() {
        if (selectedCensor !in censors.indices) return
        censors.removeAt(selectedCensor)
        selectCensor(-1)
    }

    private fun selectCensor(index: Int) {
        if (selectedCensor == index) return
        selectedCensor = index
        onCensorSelectionChanged?.invoke(censors.getOrNull(index)?.color)
    }

    private enum class DragMode { NONE, PAN, CROP_MOVE, CROP_RESIZE, CENSOR_MOVE, CENSOR_RESIZE, CENSOR_CREATE, STROKE }

    fun setBitmap(value: Bitmap) {
        setAnimatedDrawable(null)
        bitmap = value
        onImageSized()
        requestLayout()
        invalidate()
    }

    private fun onImageSized() {
        val frameCrop = pendingFrameCrop
        if (frameCrop != null || !wantedCropKnown) {
            wantedCrop.set(RotatedCrop.fromFrameNormalised(frameCrop ?: RectF(0f, 0f, 1f, 1f), imageWidth.toFloat(), imageHeight.toFloat(), userRotation))
            wantedCropKnown = true
            pendingFrameCrop = null
        }
        layoutQuarter = userRotation
        fitCropToAngle()
        rememberCrop()
        strokePathsStale = true
        startHistory()
    }

    /** The image as edited: turned, and grown by [pictureScale]. */
    private fun rotatedCrop(): RotatedCrop? =
            if (imageWidth > 0 && imageHeight > 0) {
                RotatedCrop(imageWidth * pictureScale, imageHeight * pictureScale, userRotation + tiltDegrees)
            } else {
                null
            }

    /**
     * Keeps the crop its size on screen as the image turns under it: where it would leave the image,
     * the image grows just enough to cover it again, and shrinks back once it no longer has to.
     */
    private fun fitCropToAngle() {
        if (imageWidth <= 0 || imageHeight <= 0) return
        pictureScale = RotatedCrop(imageWidth.toFloat(), imageHeight.toFloat(), userRotation + tiltDegrees).requiredScale(wantedCrop)
        val geometry = rotatedCrop() ?: return
        crop.set(geometry.toBoundsNormalised(wantedCrop))
        applyRatioAroundCenter(crop, cropAspectRatio)
    }

    /** What the user now sees is what they chose. */
    private fun rememberCrop() {
        val geometry = rotatedCrop() ?: return
        wantedCrop.set(geometry.fromBoundsNormalised(crop))
        wantedCropKnown = true
    }

    fun setAnimatedDrawable(value: Drawable?) {
        (animatedDrawable as? Animatable)?.stop()
        animatedDrawable?.callback = null
        animatedDrawable = value
        if (value != null) {
            bitmap = null
            value.callback = this
            value.setBounds(0, 0, imageWidth, imageHeight)
            onImageSized()
            (value as? Animatable)?.start()
        }
        requestLayout()
        invalidate()
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === animatedDrawable || super.verifyDrawable(who)

    /** On to the next multiple of 90°. */
    fun rotateClockwise() = turnToQuarter(QuarterStep.next(rotationAngle), clockwise = true)

    /** Back to the previous multiple of 90°. */
    fun rotateCounterClockwise() = turnToQuarter(QuarterStep.previous(rotationAngle), clockwise = false)

    private fun turnToQuarter(target: Float, clockwise: Boolean) {
        angleAnimator?.cancel()
        val from = rotationAngle
        if (QuarterStep.isQuarter(from)) {
            // A whole quarter turn: the crop turns with the image, as if the photo were turned over,
            // and so does a ratio the user chose, or the crop would have to shrink to keep it.
            wantedCrop.set(if (clockwise) RotatedCrop.turnedClockwise(wantedCrop) else turnedCounterClockwise(wantedCrop))
            if (ratioTurnsWithImage) lockedCropRatio = lockedCropRatio?.let { 1f / it }
            setRotationAngle(target, final = true)
            relayoutTo(userRotation)
            gesture.clampPan()
            animateRotation(fromDegrees = if (clockwise) -90f else 90f)
        } else {
            // Straightening a tilt: the crop stays where it is on screen, as with the dial.
            animateAngle(from, target)
        }
    }

    private fun turnedCounterClockwise(rect: RectF) =
            RotatedCrop.turnedClockwise(RotatedCrop.turnedClockwise(RotatedCrop.turnedClockwise(rect)))

    private fun animateAngle(from: Float, to: Float) {
        // The short way round, so 350° -> 0° turns ten degrees rather than back through 180°.
        var delta = to - from
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        angleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ROTATION_ANIMATION_MS * 2
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                setRotationAngle(from + delta * it.animatedFraction, final = false)
                onRotationChanged?.invoke(rotationAngle)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    setRotationAngle(to, final = true)
                    onRotationChanged?.invoke(rotationAngle)
                }
            })
            start()
        }
    }

    /**
     * Sets the whole angle from the host's dial; [final] once it settles, to record it for undo. The
     * image turns under the crop, which keeps its place and size on screen.
     */
    fun setRotationAngle(degrees: Float, final: Boolean) {
        val split = RotationSplit.of(degrees)
        userRotation = split.quarterTurns
        tiltDegrees = split.tiltDegrees
        fitCropToAngle()
        invalidate()
        if (final) commitHistory()
    }

    private fun fittedScale(quarter: Int): Float {
        val (frameWidth, frameHeight) = RotatedCrop.frameSize(imageWidth.toFloat(), imageHeight.toFloat(), quarter)
        // Inset the image so handles sitting on its edge aren't jammed against the screen edge.
        // Pinching out below 1x gives more room than this when a shot needs it.
        val pad = max(dp(28f), min(width, height) * EDGE_INSET_FRACTION)
        val availableW = width - pad * 2
        val availableH = height - pad * 2
        if (availableW <= 0 || availableH <= 0 || frameWidth <= 0 || frameHeight <= 0) return 0f
        return min(availableW / frameWidth, availableH / frameHeight)
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
            duration = ROTATION_ANIMATION_MS * 2
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                layoutZoom = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * Eases an angle that changed all at once (an undo, a redo) in from [previousAngle]. Whole
     * quarter turns spin everything, crop included, as the 90° buttons do; a tilt turns only the
     * image, as the dial does, since the crop did not move with it.
     */
    private fun animateTurnFrom(previousAngle: Float) {
        var delta = previousAngle - rotationAngle
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        if (abs(delta) < 0.01f) return
        if (QuarterStep.isQuarter(delta)) {
            animateRotation(fromDegrees = delta)
            return
        }
        pictureTurnAnimator?.cancel()
        pictureTurnAnimator = ValueAnimator.ofFloat(delta, 0f).apply {
            duration = ROTATION_ANIMATION_MS * 2
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pictureTurn = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * The geometry snaps to the new orientation immediately; this just spins the last quarter turn
     * back in so the change reads as a rotation rather than a jump.
     */
    private fun animateRotation(fromDegrees: Float) {
        rotationAnimator?.cancel()
        rotationAnimator = ValueAnimator.ofFloat(fromDegrees, 0f).apply {
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
        angleAnimator?.cancel()
        userRotation = 0
        tiltDegrees = 0f
        pictureScale = 1f
        relayoutTo(0)
        crop.set(0f, 0f, 1f, 1f)
        rememberCrop()
        censors.clear()
        selectCensor(-1)
        strokes.clear()
        strokePathsStale = true
        abandonStroke()
        gesture.reset()
        applyRatioAroundCenter(crop, cropAspectRatio)
        rememberCrop()
        clearSnapGuides()
        invalidate()
        commitHistory()
    }

    fun deleteSelectedCensor() {
        if (selectedCensor !in censors.indices) return
        censors.removeAt(selectedCensor)
        selectCensor(-1)
        invalidate()
        commitHistory()
    }

    /** Colors the selected censor, if any, and every censor drawn after. */
    fun applyCensorColor(color: Int) {
        censorColor = color
        censors.getOrNull(selectedCensor)?.let {
            it.color = color
            invalidate()
            commitHistory()
        }
    }

    fun currentEdits() = ImageEditorEdits(
            userRotation = userRotation,
            crop = frameCrop(),
            censors = censors.map { CensorEdit(RectF(it.rect), it.color) },
            tiltDegrees = tiltDegrees,
            strokes = strokes.toList(),
    )

    /** The crop the way the exporters take it: normalised against the frame at the quarter turn. */
    private fun frameCrop(): RectF {
        pendingFrameCrop?.let { return RectF(it) }
        val geometry = rotatedCrop() ?: return RectF(crop)
        // What the crop covers of the image at its own size, not grown.
        val onImage = geometry.fromBoundsNormalised(crop).scaledBy(1f / pictureScale)
        return RotatedCrop.toFrameNormalised(onImage, imageWidth.toFloat(), imageHeight.toFloat(), userRotation)
    }

    private fun RectF.scaledBy(factor: Float) = RectF(left * factor, top * factor, right * factor, bottom * factor)

    fun restoreEdits(edits: ImageEditorEdits) = applyEdits(edits, censorRatios = emptyList(), scale = 1f)

    /** [scale] is how far the image had grown, so the crop comes back at the size it was shown at. */
    private fun applyEdits(edits: ImageEditorEdits, censorRatios: List<Float?>, scale: Float) {
        angleAnimator?.cancel()
        userRotation = edits.userRotation
        tiltDegrees = edits.tiltDegrees
        pictureScale = scale
        if (imageWidth > 0 && imageHeight > 0) {
            val onImage = RotatedCrop.fromFrameNormalised(edits.crop, imageWidth.toFloat(), imageHeight.toFloat(), userRotation)
            wantedCrop.set(onImage.scaledBy(scale))
            wantedCropKnown = true
            rotatedCrop()?.let { crop.set(it.toBoundsNormalised(wantedCrop)) }
            relayoutTo(userRotation)
        } else {
            pendingFrameCrop = RectF(edits.crop)
        }
        censors.clear()
        edits.censors.forEachIndexed { index, censor ->
            censors.add(CensorBox(RectF(censor.rect), censorRatios.getOrNull(index), censor.color))
        }
        selectCensor(-1)
        strokes.clear()
        strokes.addAll(edits.strokes)
        strokePathsStale = true
        invalidate()
    }

    private fun snapshot() = Snapshot(currentEdits(), lockedCropRatio, censors.map { it.aspectRatio }, pictureScale)

    /** Carries on from a history saved with the edits being reopened, once the image has loaded. */
    fun resumeHistory(saved: EditHistory<*>) {
        resumableHistory = saved
    }

    fun historyToKeep(): EditHistory<*> = history

    @Suppress("UNCHECKED_CAST")
    private fun startHistory() {
        if (historyStarted || imageWidth <= 0 || imageHeight <= 0) return
        historyStarted = true
        val resumed = resumableHistory?.takeIf { it.current is Snapshot } as EditHistory<Snapshot>?
        resumableHistory = null
        if (resumed != null) {
            history = resumed
            // Its exact state, growth and ratios included, which the edits alone do not carry.
            restoreFromHistory(resumed.current)
        } else {
            history.reset(snapshot())
            onHistoryChanged?.invoke()
        }
    }

    private fun commitHistory() {
        if (historyStarted && history.commit(snapshot())) onHistoryChanged?.invoke()
    }

    fun undo() = restoreFromHistory(history.undo())

    fun redo() = restoreFromHistory(history.redo())

    private fun restoreFromHistory(snapshot: Snapshot?) {
        snapshot ?: return
        abandonStroke()
        dragMode = DragMode.NONE
        val previousAngle = rotationAngle
        lockedCropRatio = snapshot.cropRatio
        applyEdits(snapshot.edits, snapshot.censorRatios, snapshot.pictureScale)
        animateTurnFrom(previousAngle)
        clearSnapGuides()
        gesture.clampPan()
        onHistoryChanged?.invoke()
    }

    private val sideways get() = userRotation % 180 != 0

    /** The image's own ratio, reduced, to offer as the starting point for a custom one. */
    fun displayedAspectRatio(): Pair<Int, Int>? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val (width, height) = RotatedCrop.frameSize(imageWidth.toFloat(), imageHeight.toFloat(), userRotation)
        return reduceRatio(width.toInt(), height.toInt())
    }

    /** A ratio in output pixels, in units of [crop], which is normalised against the turned image's box. */
    private fun normalisedRatio(ratio: Float?): Float? {
        val geometry = rotatedCrop() ?: return null
        return CropRatio.normalise(ratio, geometry.boundsWidth, geometry.boundsHeight)
    }

    private fun censorNormalisedRatio(sourceRatio: Float?): Float? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        return CropRatio.normalise(sourceRatio, imageWidth.toFloat(), imageHeight.toFloat())
    }

    /** A ratio as seen on screen and in unrotated-image terms differ by the quarter turns. */
    private fun flipIfSideways(ratio: Float?) = ratio?.let { if (sideways) 1f / it else it }

    private fun applyRatioAroundCenter(rect: RectF, ratio: Float?) {
        CropRatio.fitAroundCenter(rect, normalisedRatio(ratio) ?: return)
    }

    /** True when the aspect tool would act on a censor rather than on the crop window. */
    fun isCensorSelected() = tool == Tool.CENSOR && selectedCensor in censors.indices

    /** The locked ratio of whatever the aspect tool acts on right now. */
    fun selectionAspectRatio(): Float? =
            if (isCensorSelected()) flipIfSideways(censors[selectedCensor].aspectRatio) else cropAspectRatio

    fun applySelectionAspectRatio(ratio: Float?) {
        if (isCensorSelected()) {
            censors[selectedCensor].let {
                it.aspectRatio = flipIfSideways(ratio)
                censorNormalisedRatio(it.aspectRatio)?.let { k -> CropRatio.fitAroundCenter(it.rect, k) }
            }
        } else {
            cropAspectRatio = ratio
        }
        invalidate()
        commitHistory()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageWidth <= 0 || imageHeight <= 0) return
        computeGeometry()

        // Spin the whole composition, so the overlays stay locked to the image mid-animation.
        val spinning = animatedRotation != 0f
        if (spinning) {
            canvas.save()
            canvas.rotate(animatedRotation, width / 2f, height / 2f)
        }

        val drawable = animatedDrawable
        if (drawable != null) {
            val saved = canvas.save()
            canvas.concat(drawMatrix)
            drawable.draw(canvas)
            canvas.restoreToCount(saved)
        } else {
            bitmap?.let { canvas.drawBitmap(it, drawMatrix, bitmapPaint) }
        }
        drawAnnotations(canvas)

        val cropScreen = crop.toScreen()
        drawDimOutside(canvas, cropScreen)

        when (tool) {
            Tool.CROP -> {
                canvas.drawRect(cropScreen, borderPaint)
                drawGrid(canvas, cropScreen, 3, gridPaint)
                drawCornerHandles(canvas, cropScreen)
            }
            Tool.ROTATE -> {
                canvas.drawRect(cropScreen, borderPaint)
                drawGrid(canvas, cropScreen, ROTATE_GRID_DIVISIONS, fineGridPaint)
                drawCornerHandles(canvas, cropScreen)
            }
            Tool.CENSOR -> {
                canvas.drawRect(cropScreen, gridPaint)
                censors.getOrNull(selectedCensor)?.let { selected ->
                    inSourceSpace(canvas) {
                        val r = selected.rect.toSourceScreen()
                        canvas.drawRect(r, selectionPaint)
                        drawCornerHandles(canvas, r, skipTopRight = true)
                        drawDeleteBadge(canvas, r)
                    }
                }
            }
            Tool.DRAW -> canvas.drawRect(cropScreen, gridPaint)
        }

        if (snapInSource) inSourceSpace(canvas) { drawSnapGuides(canvas, sourceRect) } else drawSnapGuides(canvas, imageRect)

        if (spinning) canvas.restore()
    }

    private inline fun inSourceSpace(canvas: Canvas, block: () -> Unit) {
        val saved = canvas.save()
        canvas.rotate(userRotation + tiltDegrees, imageRect.centerX(), imageRect.centerY())
        block()
        canvas.restoreToCount(saved)
    }

    private fun drawAnnotations(canvas: Canvas) {
        if (censors.isEmpty() && strokes.isEmpty() && liveStrokeSize == 0) return
        val w = imageWidth.toFloat()
        val h = imageHeight.toFloat()
        if (strokePathsStale) rebuildStrokePaths()
        val saved = canvas.save()
        canvas.concat(drawMatrix)
        canvas.clipRect(0f, 0f, w, h)
        censors.forEach {
            censorPaint.color = it.color
            canvas.drawRect(it.rect.left * w, it.rect.top * h, it.rect.right * w, it.rect.bottom * h, censorPaint)
        }
        strokes.forEachIndexed { index, stroke ->
            strokePaint.color = stroke.color
            strokePaint.strokeWidth = stroke.widthIn(w, h)
            canvas.drawPath(strokePaths[index], strokePaint)
        }
        if (liveStrokeSize > 0) {
            strokePaint.color = brushColor
            strokePaint.strokeWidth = liveStrokeWidth * min(w, h)
            canvas.drawPath(BrushStroke(liveStroke.copyOf(liveStrokeSize), brushColor, liveStrokeWidth).toPath(w, h), strokePaint)
        }
        canvas.restoreToCount(saved)
    }

    private fun rebuildStrokePaths() {
        strokePaths.clear()
        strokes.forEach { strokePaths.add(it.toPath(imageWidth.toFloat(), imageHeight.toFloat())) }
        strokePathsStale = false
    }

    private fun computeGeometry() {
        val geometry = rotatedCrop() ?: return
        // Sized for the settled orientation, not the live angle, so turning never zooms the image.
        val fittedScale = fittedScale(layoutQuarter) * layoutZoom
        if (fittedScale <= 0f) return
        // Zooming imageRect is enough for the whole screen: the crop window, censors and handles
        // are all projected through it.
        gesture.contentWidth = geometry.boundsWidth * fittedScale
        gesture.contentHeight = geometry.boundsHeight * fittedScale
        gesture.viewportWidth = width.toFloat()
        gesture.viewportHeight = height.toFloat()
        val scale = fittedScale * gesture.zoom
        val drawnW = geometry.boundsWidth * scale
        val drawnH = geometry.boundsHeight * scale
        val cx = width / 2f + gesture.panX
        val cy = height / 2f + gesture.panY
        imageRect.set(cx - drawnW / 2f, cy - drawnH / 2f, cx + drawnW / 2f, cy + drawnH / 2f)

        val sourceW = imageWidth * scale * pictureScale
        val sourceH = imageHeight * scale * pictureScale
        sourceRect.set(cx - sourceW / 2f, cy - sourceH / 2f, cx + sourceW / 2f, cy + sourceH / 2f)

        drawMatrix.reset()
        drawMatrix.postTranslate(-imageWidth / 2f, -imageHeight / 2f)
        drawMatrix.postRotate(userRotation + tiltDegrees + pictureTurn)
        drawMatrix.postScale(scale * pictureScale, scale * pictureScale)
        drawMatrix.postTranslate(cx, cy)

        toSourceSpace.setRotate(-(userRotation + tiltDegrees), cx, cy)
    }

    private fun drawDimOutside(canvas: Canvas, rect: RectF) {
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, rect.top, dimPaint)
        canvas.drawRect(imageRect.left, rect.bottom, imageRect.right, imageRect.bottom, dimPaint)
        canvas.drawRect(imageRect.left, rect.top, rect.left, rect.bottom, dimPaint)
        canvas.drawRect(rect.right, rect.top, imageRect.right, rect.bottom, dimPaint)
    }

    private fun drawSnapGuides(canvas: Canvas, reference: RectF) {
        if (dragMode == DragMode.NONE) return
        if (snappedX) canvas.drawLine(reference.centerX(), reference.top, reference.centerX(), reference.bottom, snapGuidePaint)
        if (snappedY) canvas.drawLine(reference.left, reference.centerY(), reference.right, reference.centerY(), snapGuidePaint)
    }

    private fun drawGrid(canvas: Canvas, rect: RectF, divisions: Int, paint: Paint) {
        val stepW = rect.width() / divisions
        val stepH = rect.height() / divisions
        for (i in 1 until divisions) {
            canvas.drawLine(rect.left + stepW * i, rect.top, rect.left + stepW * i, rect.bottom, paint)
            canvas.drawLine(rect.left, rect.top + stepH * i, rect.right, rect.top + stepH * i, paint)
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

    /** Turns a screen point back into the unrotated space annotations are edited in. */
    private fun mapToSource(x: Float, y: Float) {
        touchPoint[0] = x
        touchPoint[1] = y
        toSourceSpace.mapPoints(touchPoint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageWidth <= 0 || imageHeight <= 0 || imageRect.isEmpty) return false
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount >= 2) {
            // A second finger turns the gesture into zoom/pan; abandon any edit it started as.
            beginPinch(event)
            parent?.requestDisallowInterceptTouchEvent(true)
            invalidate()
            return true
        }
        if (gesture.isPinching) {
            if (tool == Tool.ROTATE) trackTwist(event)
            val handled = gesture.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (tool == Tool.ROTATE) setRotationAngle(rotationAngle, final = true) else commitHistory()
            }
            return handled
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                clearSnapGuides()
                mapToSource(event.x, event.y)
                val sx = touchPoint[0]
                val sy = touchPoint[1]
                lastTouchX = event.x
                lastTouchY = event.y
                lastSourceX = sx
                lastSourceY = sy
                dragMode = when (tool) {
                    Tool.CROP -> beginCropDrag(event.x, event.y, sx, sy, allowCensorPick = true)
                    Tool.ROTATE -> beginCropDrag(event.x, event.y, sx, sy, allowCensorPick = false)
                    Tool.CENSOR -> beginCensorDrag(sx, sy)
                    Tool.DRAW -> beginStroke(event.x, event.y, sx, sy)
                }
                snapInSource = dragMode == DragMode.CENSOR_MOVE || dragMode == DragMode.CENSOR_RESIZE || dragMode == DragMode.CENSOR_CREATE
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
                mapToSource(event.x, event.y)
                val sx = touchPoint[0]
                val sy = touchPoint[1]
                applyDrag(event.x, event.y, sx, sy)
                lastTouchX = event.x
                lastTouchY = event.y
                lastSourceX = sx
                lastSourceY = sy
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode == DragMode.CENSOR_CREATE) {
                    val created = censors.getOrNull(selectedCensor)?.rect?.toSourceScreen()
                    if (created != null && created.width() < tapSlop && created.height() < tapSlop) {
                        // A tap rather than a drag: discard it and leave censor mode. Judged on
                        // screen distance, so a deliberately drawn small censor survives.
                        discardPendingCensor()
                        tool = Tool.CROP
                        onToolChanged?.invoke(Tool.CROP)
                    }
                }
                if (dragMode == DragMode.STROKE) finishStroke()
                if (dragMode == DragMode.CROP_MOVE || dragMode == DragMode.CROP_RESIZE) rememberCrop()
                dragMode = DragMode.NONE
                clearSnapGuides()
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                commitHistory()
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
                    setRotationAngle(next, final = false)
                    onRotationChanged?.invoke(next)
                }
            }
        }
    }

    private fun beginCropDrag(x: Float, y: Float, sx: Float, sy: Float, allowCensorPick: Boolean): DragMode {
        val rect = crop.toScreen()
        val handle = nearestHandle(rect, x, y)
        if (handle != null) {
            dragCornerX = handle.first
            dragCornerY = handle.second
            return DragMode.CROP_RESIZE
        }
        // Touching an existing censor is a request to go back and adjust it. Crop handles win
        // when both are in range, so the crop is never impossible to grab.
        if (allowCensorPick) {
            for (index in censors.indices.reversed()) {
                if (censors[index].rect.toSourceScreen().contains(sx, sy)) {
                    // The tool setter clears the selection, so it has to be applied first.
                    tool = Tool.CENSOR
                    onToolChanged?.invoke(Tool.CENSOR)
                    selectCensor(index)
                    return DragMode.CENSOR_MOVE
                }
            }
        }
        // Inside the box moves it; anywhere else navigates the image, so a tall image zoomed in is
        // not pannable only with two fingers. A box filling the frame has nowhere to move, so it
        // gives the drag up rather than swallowing it.
        if (rect.contains(x, y) && cropCanMove()) return DragMode.CROP_MOVE
        return if (gesture.zoom > 1f) DragMode.PAN else DragMode.NONE
    }

    private fun cropCanMove() = crop.width() < 0.999f || crop.height() < 0.999f

    private fun beginCensorDrag(sx: Float, sy: Float): DragMode {
        snapInSource = true
        censors.getOrNull(selectedCensor)?.let { selected ->
            val screen = selected.rect.toSourceScreen()
            if (isOnDeleteBadge(screen, sx, sy)) {
                deleteSelectedCensor()
                return DragMode.NONE
            }
            val handle = nearestHandle(screen, sx, sy)
            if (handle != null) {
                dragCornerX = handle.first
                dragCornerY = handle.second
                return DragMode.CENSOR_RESIZE
            }
        }
        for (index in censors.indices.reversed()) {
            if (censors[index].rect.toSourceScreen().contains(sx, sy)) {
                selectCensor(index)
                return DragMode.CENSOR_MOVE
            }
        }
        if (!sourceRect.contains(sx, sy)) return DragMode.NONE
        val nx = snapX(sourceNormalisedX(sx))
        val ny = snapY(sourceNormalisedY(sy))
        censors.add(CensorBox(RectF(nx, ny, nx, ny), color = censorColor))
        selectCensor(censors.lastIndex)
        createAnchorX = nx
        createAnchorY = ny
        return DragMode.CENSOR_CREATE
    }

    private fun beginStroke(x: Float, y: Float, sx: Float, sy: Float): DragMode {
        if (!sourceRect.contains(sx, sy)) return if (gesture.zoom > 1f) DragMode.PAN else DragMode.NONE
        val shorterSide = min(sourceRect.width(), sourceRect.height())
        if (shorterSide <= 0f) return DragMode.NONE
        liveStrokeWidth = dp(brushSizeDp) / shorterSide
        liveStrokeSize = 0
        appendStrokePoint(sx, sy)
        lastStrokeScreenX = x
        lastStrokeScreenY = y
        return DragMode.STROKE
    }

    private fun appendStrokePoint(sx: Float, sy: Float) {
        if (liveStrokeSize + 2 > liveStroke.size) liveStroke = liveStroke.copyOf(liveStroke.size * 2)
        // Unclamped: a line running off the edge must keep its direction, and is clipped anyway.
        liveStroke[liveStrokeSize++] = (sx - sourceRect.left) / sourceRect.width()
        liveStroke[liveStrokeSize++] = (sy - sourceRect.top) / sourceRect.height()
    }

    private fun finishStroke() {
        if (liveStrokeSize >= 2) {
            val stroke = BrushStroke(liveStroke.copyOf(liveStrokeSize), brushColor, liveStrokeWidth)
            strokes.add(stroke)
            if (!strokePathsStale) strokePaths.add(stroke.toPath(imageWidth.toFloat(), imageHeight.toFloat()))
        }
        liveStrokeSize = 0
    }

    private fun abandonStroke() {
        liveStrokeSize = 0
        if (dragMode == DragMode.STROKE) dragMode = DragMode.NONE
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

    private fun applyDrag(x: Float, y: Float, sx: Float, sy: Float) {
        clearSnapGuides()

        when (dragMode) {
            DragMode.PAN -> gesture.panBy(x - lastTouchX, y - lastTouchY)
            DragMode.CROP_MOVE -> {
                val before = RectF(crop)
                moveWithSnap(crop, (x - lastTouchX) / imageRect.width(), (y - lastTouchY) / imageRect.height())
                // Held at the image's edge, the finger's own position stops counting until it returns.
                if (keepCropOnImage(before, slide = true)) unsnappedRect.set(crop)
            }
            DragMode.CROP_RESIZE -> {
                val before = RectF(crop)
                val nx = snapX(normalisedX(x))
                val ny = snapY(normalisedY(y))
                val k = normalisedRatio(cropAspectRatio)
                if (k != null) {
                    CropRatio.resize(crop, k, nx, ny, dragCornerX, dragCornerY, minCropNormalisedWidth(), minCropNormalisedHeight())
                } else {
                    resizeCorner(crop, nx, ny, minCropNormalisedWidth(), minCropNormalisedHeight())
                }
                // Sliding along one axis would change a locked shape.
                keepCropOnImage(before, slide = k == null)
            }
            DragMode.CENSOR_MOVE -> censors.getOrNull(selectedCensor)?.let {
                moveWithSnap(it.rect, (sx - lastSourceX) / sourceRect.width(), (sy - lastSourceY) / sourceRect.height())
            }
            DragMode.CENSOR_RESIZE -> censors.getOrNull(selectedCensor)?.let {
                val nx = snapX(sourceNormalisedX(sx))
                val ny = snapY(sourceNormalisedY(sy))
                val k = censorNormalisedRatio(it.aspectRatio)
                if (k != null) {
                    CropRatio.resize(it.rect, k, nx, ny, dragCornerX, dragCornerY, minCensorNormalisedWidth(), minCensorNormalisedHeight())
                } else {
                    resizeCorner(it.rect, nx, ny, minCensorNormalisedWidth(), minCensorNormalisedHeight())
                }
            }
            DragMode.CENSOR_CREATE -> {
                val nx = snapX(sourceNormalisedX(sx))
                val ny = snapY(sourceNormalisedY(sy))
                censors.getOrNull(selectedCensor)?.rect?.set(
                        min(createAnchorX, nx), min(createAnchorY, ny), max(createAnchorX, nx), max(createAnchorY, ny)
                )
            }
            DragMode.STROKE -> {
                if (hypot(x - lastStrokeScreenX, y - lastStrokeScreenY) >= strokeStep) {
                    appendStrokePoint(sx, sy)
                    lastStrokeScreenX = x
                    lastStrokeScreenY = y
                }
            }
            DragMode.NONE -> Unit
        }
    }

    /**
     * Pulls [crop] back from wherever it left the turned image, toward [before], which was on it.
     * With [slide], whichever axis still has room keeps going, so the box glides along the edge.
     * Returns whether it had to.
     */
    private fun keepCropOnImage(before: RectF, slide: Boolean): Boolean {
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

    private fun sourceNormalisedX(x: Float) = ((x - sourceRect.left) / sourceRect.width()).coerceIn(0f, 1f)

    private fun sourceNormalisedY(y: Float) = ((y - sourceRect.top) / sourceRect.height()).coerceIn(0f, 1f)

    private fun clearSnapGuides() {
        snappedX = false
        snappedY = false
    }

    private val snapReference get() = if (snapInSource) sourceRect else imageRect

    /** Snapping is judged on screen distance, so it stays a fixed grab distance at any zoom. */
    private fun snapX(nx: Float): Float {
        val extent = snapReference.width()
        if (!snapToCenter || extent <= 0f) return nx
        val snapped = abs(nx - 0.5f) * extent <= snapDistance
        if (snapped) snappedX = true
        return if (snapped) 0.5f else nx
    }

    private fun snapY(ny: Float): Float {
        val extent = snapReference.height()
        if (!snapToCenter || extent <= 0f) return ny
        val snapped = abs(ny - 0.5f) * extent <= snapDistance
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

    private fun RectF.toSourceScreen() = RectF(
            sourceRect.left + left * sourceRect.width(),
            sourceRect.top + top * sourceRect.height(),
            sourceRect.left + right * sourceRect.width(),
            sourceRect.top + bottom * sourceRect.height()
    )

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
