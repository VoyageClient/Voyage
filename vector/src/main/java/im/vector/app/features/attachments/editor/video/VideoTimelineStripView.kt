/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A filmstrip with draggable trim handles and a playhead, modelled on Telegram's video timeline:
 * the strip is inset so both handles stay fully on screen and clear of the system back gesture.
 *
 * Holding a handle still zooms the strip to a per-frame ruler, where dragging steps one frame at a
 * time with a haptic tick and the strip pans to keep the current frame centred.
 */
class VideoTimelineStripView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    fun interface Listener {
        fun onTrimChanged(startUs: Long, endUs: Long, dragging: Boolean)
    }

    var listener: Listener? = null

    /** Playhead moved to a position; [dragging] is false on the final update of a gesture. */
    var onScrub: ((Long, Boolean) -> Unit)? = null

    /**
     * Per-frame mode entered or left, carrying the handle's position so the caller can park
     * playback on it. Times should be shown at frame precision while it is on.
     */
    var onFineModeChanged: ((fine: Boolean, positionUs: Long) -> Unit)? = null

    var durationUs: Long = 0
        set(value) {
            field = value
            startUs = 0
            endUs = value
            invalidate()
        }

    var frameRate: Float = DEFAULT_FRAME_RATE
        set(value) {
            field = if (value > 0f) value else DEFAULT_FRAME_RATE
            invalidate()
        }

    var startUs: Long = 0
        private set
    var endUs: Long = 0
        private set

    var playheadUs: Long = 0
        set(value) {
            field = value
            invalidate()
        }

    private val thumbnails = mutableListOf<Bitmap>()
    private var thumbnailCount = 0

    private val outerInset = dp(12f)
    private val handleWidth = dp(10f)
    private val cornerRadius = dp(6f)
    private val touchSlop = dp(16f)
    private val pixelsPerFrame = dp(28f)
    private val edgeSnap = dp(24f)
    private val dragSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val rulerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B1B1B.toInt() }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80FFFFFF.toInt() }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val chevronPath = Path()
    private val srcRect = Rect()
    private val dstRect = Rect()
    private val scratch = RectF()
    private val exclusionRects = mutableListOf(Rect())

    private var dragging = Drag.NONE
    private var trimChanged = false
    private var pressOffset = 0f
    private var downX = 0f
    private var lastTouchX = 0f

    private var fineMode = false
    private var fineProgress = 0f
    private var fineCentreUs = 0L
    private var fineFingerX = 0f
    private var fineAccumulator = 0f
    private var fineRate = 0f
    private var fineBaseFrame = 0L
    private var fineRepeatSteps = 0L
    private var fineAnimator: ValueAnimator? = null

    private val longPressRunnable = Runnable { enterFineMode() }

    private enum class Drag { NONE, START, END, PLAYHEAD }

    private val frameDurationUs get() = (1_000_000f / frameRate).toLong().coerceAtLeast(1)

    /** Frame slots are filled as extraction progresses; [count] fixes the layout up front. */
    fun prepareThumbnails(count: Int) {
        recycleThumbnails()
        thumbnailCount = count
        invalidate()
    }

    fun addThumbnail(bitmap: Bitmap) {
        thumbnails.add(bitmap)
        invalidate()
    }

    fun setTrim(startUs: Long, endUs: Long) {
        this.startUs = startUs.coerceIn(0, durationUs)
        this.endUs = endUs.coerceIn(this.startUs, durationUs)
        invalidate()
    }

    private fun recycleThumbnails() {
        thumbnails.forEach { it.recycle() }
        thumbnails.clear()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(longPressRunnable)
        fineAnimator?.cancel()
        recycleThumbnails()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Without this the edge handles compete with the system back gesture and barely drag.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exclusionRects[0].set(0, 0, right - left, bottom - top)
            systemGestureExclusionRects = exclusionRects
        }
    }

    private val trackLeft get() = outerInset + handleWidth
    private val trackWidth get() = width - (outerInset + handleWidth) * 2

    // Both zoom levels are affine maps of time onto x, so the transition is just a blend of the two.
    private val wholeScale get() = trackWidth / durationUs.toFloat()
    private val fineScale get() = pixelsPerFrame / frameDurationUs.toFloat()
    private val scale get() = lerp(wholeScale, fineScale, fineProgress)
    private val originX get() = lerp(trackLeft, width / 2f - fineCentreUs * fineScale, fineProgress)

    private fun timeToX(us: Long): Float = originX + us * scale

    private fun xToTime(x: Float): Long = ((x - originX) / scale).toLong().coerceIn(0, durationUs)

    private fun lerp(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction

    override fun onDraw(canvas: Canvas) {
        if (durationUs <= 0 || trackWidth <= 0) return
        val top = dp(2f)
        val bottom = height - dp(2f)
        val startX = timeToX(startUs)
        val endX = timeToX(endUs)

        // Zoomed in the video occupies only part of the strip, and the ruler must stop where it does.
        val contentLeft = max(outerInset, timeToX(0))
        val contentRight = min(width - outerInset, timeToX(durationUs))

        canvas.save()
        scratch.set(contentLeft, top, contentRight, bottom)
        canvas.clipRect(scratch)
        if (fineProgress < 1f) drawFilmstrip(canvas, top, bottom)
        if (fineProgress > 0f) drawRuler(canvas, contentLeft, contentRight, top, bottom)
        canvas.drawRect(contentLeft, top, startX, bottom, dimPaint)
        canvas.drawRect(endX, top, contentRight, bottom, dimPaint)
        canvas.restore()

        canvas.save()
        scratch.set(outerInset, top, width - outerInset, bottom)
        canvas.clipRect(scratch)
        drawSelectionFrame(canvas, startX, endX, top, bottom)
        drawPlayhead(canvas, top, bottom)
        if (fineProgress > 0f) drawEdgeHints(canvas, top, bottom)
        canvas.restore()
    }

    private fun drawFilmstrip(canvas: Canvas, top: Float, bottom: Float) {
        if (thumbnailCount == 0) return
        framePaint.alpha = (255 * (1f - fineProgress)).toInt()
        val slotUs = durationUs / thumbnailCount
        thumbnails.forEachIndexed { index, bitmap ->
            srcRect.set(0, 0, bitmap.width, bitmap.height)
            dstRect.set(
                    timeToX(index * slotUs).toInt(),
                    top.toInt(),
                    timeToX((index + 1) * slotUs).toInt(),
                    bottom.toInt()
            )
            canvas.drawBitmap(bitmap, srcRect, dstRect, framePaint)
        }
    }

    /** A ruler tick per frame, so a per-frame drag has something to read against. */
    private fun drawRuler(canvas: Canvas, contentLeft: Float, contentRight: Float, top: Float, bottom: Float) {
        val alpha = (255 * fineProgress).toInt()
        rulerPaint.alpha = alpha
        canvas.drawRect(contentLeft, top, contentRight, bottom, rulerPaint)

        val firstFrame = floor(xToTimeUnclamped(contentLeft) / frameDurationUs.toDouble()).toLong()
        val lastFrame = ceil(xToTimeUnclamped(contentRight) / frameDurationUs.toDouble()).toLong()
        if (lastFrame - firstFrame > MAX_TICKS) return

        val tickWidth = dp(1f)
        for (frame in firstFrame.coerceAtLeast(0)..lastFrame) {
            val frameUs = frame * frameDurationUs
            if (frameUs > durationUs) break
            val x = timeToX(frameUs)
            val major = frame % TICKS_PER_MAJOR == 0L
            val tickHeight = if (major) (bottom - top) * 0.42f else (bottom - top) * 0.22f
            tickPaint.alpha = if (major) alpha else (alpha * 0.55f).toInt()
            canvas.drawRect(x - tickWidth / 2f, bottom - tickHeight, x + tickWidth / 2f, bottom, tickPaint)
        }
    }

    private fun xToTimeUnclamped(x: Float): Long = ((x - originX) / scale).toLong()

    /** A bar either side of the selection, drawn as a rounded frame with its middle cut out. */
    @Suppress("DEPRECATION")
    private fun drawSelectionFrame(canvas: Canvas, startX: Float, endX: Float, top: Float, bottom: Float) {
        canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 0xFF, Canvas.ALL_SAVE_FLAG)
        scratch.set(startX - handleWidth, top, endX + handleWidth, bottom)
        canvas.drawRoundRect(scratch, cornerRadius, cornerRadius, selectionPaint)
        scratch.set(startX, top + dp(2f), endX, bottom - dp(2f))
        canvas.drawRect(scratch, cutPaint)
        canvas.restore()

        val gripHeight = dp(12f)
        val gripWidth = dp(2f)
        val gripTop = top + (bottom - top - gripHeight) / 2f
        drawGrip(canvas, startX - handleWidth / 2f - gripWidth / 2f, gripTop, gripWidth, gripHeight)
        drawGrip(canvas, endX + handleWidth / 2f - gripWidth / 2f, gripTop, gripWidth, gripHeight)
    }

    private fun drawGrip(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        scratch.set(left, top, left + width, top + height)
        canvas.drawRoundRect(scratch, width / 2f, width / 2f, gripPaint)
    }

    /** Marks the zones that step frames, lit on the side currently stepping. */
    private fun drawEdgeHints(canvas: Canvas, top: Float, bottom: Float) {
        val centreY = (top + bottom) / 2f
        drawChevron(canvas, outerInset + dp(8f), centreY, forward = false, active = fineRate < 0f)
        drawChevron(canvas, width - outerInset - dp(8f), centreY, forward = true, active = fineRate > 0f)
    }

    private fun drawChevron(canvas: Canvas, x: Float, centreY: Float, forward: Boolean, active: Boolean) {
        val size = dp(5f)
        val direction = if (forward) 1f else -1f
        chevronPaint.alpha = ((if (active) 0.95f else 0.3f) * 255 * fineProgress).toInt()
        chevronPath.reset()
        chevronPath.moveTo(x - size * direction, centreY - size)
        chevronPath.lineTo(x + size * direction, centreY)
        chevronPath.lineTo(x - size * direction, centreY + size)
        canvas.drawPath(chevronPath, chevronPaint)
    }

    private fun drawPlayhead(canvas: Canvas, top: Float, bottom: Float) {
        if (playheadUs !in startUs..endUs) return
        val cx = timeToX(playheadUs)
        val halfWidth = dp(1.5f)
        scratch.set(cx - halfWidth, top, cx + halfWidth, bottom)
        canvas.drawRoundRect(scratch, halfWidth, halfWidth, playheadPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationUs <= 0 || trackWidth <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val startX = timeToX(startUs)
                val endX = timeToX(endUs)
                val playheadX = timeToX(playheadUs)
                // Handles win over the playhead: mis-grabbing a handle costs the user their trim.
                dragging = when {
                    abs(event.x - startX) <= touchSlop -> Drag.START
                    abs(event.x - endX) <= touchSlop -> Drag.END
                    abs(event.x - playheadX) <= touchSlop -> Drag.PLAYHEAD
                    event.x in startX..endX -> Drag.PLAYHEAD
                    else -> return false
                }
                downX = event.x
                lastTouchX = event.x
                trimChanged = false
                pressOffset = event.x - when (dragging) {
                    Drag.START -> startX
                    Drag.END -> endX
                    else -> playheadX
                }
                if (dragging == Drag.PLAYHEAD && abs(event.x - playheadX) > touchSlop) pressOffset = 0f
                if (dragging == Drag.START || dragging == Drag.END) {
                    // Taking hold of a handle is not an edit; wait for movement.
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                } else {
                    handleDrag(event.x)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                lastTouchX = event.x
                // A hold that has already turned into a drag is a drag, not a long press.
                if (!fineMode && abs(event.x - downX) > dragSlop) removeCallbacks(longPressRunnable)
                handleDrag(event.x)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                removeCallbacks(longPressRunnable)
                when (dragging) {
                    Drag.START, Drag.END -> if (trimChanged) listener?.onTrimChanged(startUs, endUs, false)
                    Drag.PLAYHEAD -> onScrub?.invoke(playheadUs, false)
                    Drag.NONE -> Unit
                }
                if (fineMode) exitFineMode()
                dragging = Drag.NONE
            }
        }
        return true
    }

    private fun enterFineMode() {
        fineMode = true
        fineFingerX = lastTouchX
        fineBaseFrame = (if (dragging == Drag.START) startUs else endUs) / frameDurationUs
        fineRepeatSteps = 0L
        fineAccumulator = 0f
        fineRate = 0f
        fineCentreUs = if (dragging == Drag.START) startUs else endUs
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onFineModeChanged?.invoke(true, fineCentreUs)
        animateFineProgress(1f)
        post(fineTicker)
    }

    private fun exitFineMode() {
        fineMode = false
        removeCallbacks(fineTicker)
        onFineModeChanged?.invoke(false, if (dragging == Drag.START) startUs else endUs)
        animateFineProgress(0f)
    }

    private fun animateFineProgress(target: Float) {
        fineAnimator?.cancel()
        fineAnimator = ValueAnimator.ofFloat(fineProgress, target).apply {
            duration = ZOOM_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fineProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun handleDrag(x: Float) {
        if (fineMode) {
            // Position picks the speed, never the frame — see repeatRate.
            fineFingerX = x
            return
        }
        val position = x - pressOffset
        applyTime(snapToEdges(position, xToTime(position)))
    }

    /**
     * The track is inset from both screen edges, so dragging as far as the finger goes still stops
     * short of the ends. Anything within a thumb's width of an end takes that end.
     */
    private fun snapToEdges(x: Float, us: Long): Long = when {
        x <= trackLeft + edgeSnap -> 0L
        x >= trackLeft + trackWidth - edgeSnap -> durationUs
        else -> us
    }

    private fun applyTime(time: Long) {
        when (dragging) {
            Drag.START -> {
                val next = time.coerceIn(0, (endUs - MIN_DURATION_US).coerceAtLeast(0))
                if (next == startUs) return
                startUs = next
                if (playheadUs < startUs) playheadUs = startUs
                if (fineMode) fineCentreUs = startUs
                trimChanged = true
                listener?.onTrimChanged(startUs, endUs, true)
            }
            Drag.END -> {
                val next = time.coerceIn((startUs + MIN_DURATION_US).coerceAtMost(durationUs), durationUs)
                if (next == endUs) return
                endUs = next
                if (playheadUs > endUs) playheadUs = endUs
                if (fineMode) fineCentreUs = endUs
                trimChanged = true
                listener?.onTrimChanged(startUs, endUs, true)
            }
            Drag.PLAYHEAD -> {
                playheadUs = time.coerceIn(startUs, endUs)
                onScrub?.invoke(playheadUs, true)
            }
            Drag.NONE -> Unit
        }
        invalidate()
    }

    /**
     * A jog wheel rather than a 1:1 drag: the screen holds only a handful of frames at this zoom, so
     * frames step past for as long as the finger is held in an edge zone. Speed has inertia, wound
     * up by holding and bled off by easing back, so a long run is one gesture and a nudge is one tap.
     */
    private val fineTicker = object : Runnable {
        override fun run() {
            val target = repeatRate()
            // Reaching an edge steps once straight away, so a flick out and back is a single frame.
            if (target != 0f && fineRate == 0f) fineAccumulator = if (target > 0f) 1f else -1f
            // Winding up is slower than bleeding off, so speed is deliberate but easy to shed.
            val ease = if (abs(target) > abs(fineRate)) RATE_ATTACK else RATE_DECAY
            fineRate += (target - fineRate) * ease
            if (abs(fineRate) < RATE_EPSILON) fineRate = 0f

            if (fineRate != 0f) {
                fineAccumulator += fineRate * FINE_TICK_MS / 1000f
                while (abs(fineAccumulator) >= 1f) {
                    val step = if (fineAccumulator > 0f) 1 else -1
                    fineAccumulator -= step
                    fineRepeatSteps += step
                }
            }
            applyFineFrame()
            if (fineMode) postDelayed(this, FINE_TICK_MS)
        }
    }

    /**
     * Frames only advance while the finger is held in one of the strip's edge zones, faster the
     * further into it. The whole middle is neutral, so a finger resting there — however unsteady —
     * never moves the frame it is parked on.
     */
    private fun repeatRate(): Float {
        val centre = width / 2f
        val offset = fineFingerX - centre
        val zone = width * EDGE_ZONE_FRACTION
        val penetration = abs(offset) - (centre - zone)
        if (penetration <= 0f) return 0f
        val throttle = (penetration / zone).coerceIn(0f, 1f)
        val rate = MIN_STEP_RATE + (MAX_STEP_RATE - MIN_STEP_RATE) * throttle * throttle
        return if (offset < 0f) -rate else rate
    }

    private fun applyFineFrame() {
        val currentUs = if (dragging == Drag.START) startUs else endUs
        val target = ((fineBaseFrame + fineRepeatSteps) * frameDurationUs).coerceIn(0, durationUs)
        if (target == currentUs) return
        performHapticFeedback(frameTickFeedback(), HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
        applyTime(target)
    }

    private fun frameTickFeedback() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        HapticFeedbackConstants.CLOCK_TICK
    } else {
        HapticFeedbackConstants.KEYBOARD_TAP
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    companion object {
        const val MIN_DURATION_US = 500_000L
        private const val DEFAULT_FRAME_RATE = 30f
        private const val ZOOM_DURATION_MS = 220L
        private const val FINE_TICK_MS = 16L

        /** Share of the strip's width at each end that steps frames; the rest is neutral. */
        private const val EDGE_ZONE_FRACTION = 0.22f
        private const val MIN_STEP_RATE = 2f
        private const val MAX_STEP_RATE = 30f
        private const val RATE_ATTACK = 0.03f
        private const val RATE_DECAY = 0.12f
        private const val RATE_EPSILON = 0.05f
        private const val TICKS_PER_MAJOR = 5L
        private const val MAX_TICKS = 600L
    }
}
