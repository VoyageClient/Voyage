/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.core.view.ViewCompat
import im.vector.lib.mediatranscode.AudioWaveform
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The waveform of a whole file, zoomed in and scrolled so the moment being played sits under the
 * middle of the view — SoundCloud's player rather than a bar squeezed to fit. What has been played
 * is white and what is to come is dimmed, so the centre reads as the playhead without a marker.
 *
 * Playback is reported a few times a second at best, so the position is carried forward from the
 * last report on every frame rather than stepping with it, and a report close to where the view had
 * got to is left alone: stepping to each one instead is what makes a waveform jitter. Dragging
 * scrubs and throws with momentum, a tap plays or pauses.
 */
class WaveformScrubView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** One peak per [AudioWaveform.SLICE_MS] of the file, each 0..1. */
    var levels: FloatArray = FloatArray(0)
        set(value) {
            field = value
            invalidate()
        }

    var durationMs: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    /** The part still kept, when a trim is being made. Anything outside it is not drawn. */
    var rangeStartMs: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var rangeEndMs: Int = Int.MAX_VALUE
        set(value) {
            field = value
            invalidate()
        }

    /** What a scrub is doing to the position, so a screen knows whether to park or resume playback. */
    enum class SeekPhase {
        /** Under a finger or still travelling: park playback and follow. */
        MOVING,

        /** The gesture is over: seek there and pick playback back up. */
        SETTLED,

        /** A touch stopped a throw: seek there, but leave playback alone — a tap decides next. */
        INTERRUPTED,
    }

    var onSeek: ((positionMs: Int, phase: SeekPhase) -> Unit)? = null
    var onTap: (() -> Unit)? = null

    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(2.5f)
    }

    private val pendingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x59FFFFFF
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(2.5f)
    }

    private val barStep = dp(4f)

    /** Bars fill the view they are given, so the band that can be swiped is the band on show. */
    private val maximumBarHalfHeight = dp(80f)
    private val configuration = ViewConfiguration.get(context)
    private val touchSlop = configuration.scaledTouchSlop
    private val scroller = OverScroller(context)

    private var velocityTracker: VelocityTracker? = null

    private var anchorPositionMs = 0
    private var anchorAt = 0L
    private var playing = false
    private var speed = 1f

    private var dragging = false
    private var flinging = false
    private var awaitingSeekTo: Int? = null
    private var awaitingUntil = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragPositionMs = 0
    private var flingStartMs = 0

    /**
     * How many slices one bar stands for, so that about [VISIBLE_WINDOW_MS] of sound fits across
     * the view: zoomed in enough to read, wide enough that a swipe travels somewhere.
     */
    private val slicesPerBar: Int
        get() {
            val bars = (width / barStep).toInt().coerceAtLeast(1)
            return (VISIBLE_WINDOW_MS / AudioWaveform.SLICE_MS / bars).coerceAtLeast(1)
        }

    private val msPerBar get() = slicesPerBar * AudioWaveform.SLICE_MS

    /**
     * How long the clip is. A picked file often arrives without one, and the peaks themselves say
     * as much — one per slice — so nothing has to wait for a player to be asked.
     */
    private val knownDurationMs: Int
        get() = if (durationMs > 0) durationMs else levels.size * AudioWaveform.SLICE_MS

    /** Scrubbing stops at the cut: what has been trimmed away is not part of the clip any more. */
    private val lowerBound get() = rangeStartMs.coerceIn(0, max(knownDurationMs, 0))
    private val upperBound get() = minOf(rangeEndMs, max(knownDurationMs, 0)).coerceAtLeast(lowerBound)

    private fun clamped(positionMs: Int) = positionMs.coerceIn(lowerBound, upperBound)
    private val pixelsPerMs get() = barStep / msPerBar

    /** Where playback really is now, carried on from the last report at the speed it is running. */
    private val positionMs: Int
        get() {
            if (dragging || flinging) return dragPositionMs
            if (!playing) return anchorPositionMs
            val elapsed = (SystemClock.uptimeMillis() - anchorAt) * speed
            return clamped((anchorPositionMs + elapsed).toInt())
        }

    /**
     * A report from the player: where it is, whether it is running, and how fast. Reports are
     * treated as corrections rather than truth — a small step forward is the noise of asking, and
     * a small step backwards is an audio sink spinning up (Bluetooth especially) reporting the
     * position it had a moment ago. Both are held through; only a real difference resyncs.
     */
    fun setPosition(positionMs: Int, playing: Boolean, speed: Float = 1f) {
        // A finger on the waveform owns where it is; the player is chasing it, not the other way
        // round, and stepping to what it reports mid-gesture is what tears the drag up.
        if (dragging || flinging) {
            this.playing = playing
            this.speed = speed
            return
        }
        val predicted = this.positionMs
        this.playing = playing
        this.speed = speed
        awaitingSeekTo?.let { target ->
            val landed = abs(positionMs - target) <= DRIFT_TOLERANCE_MS
            if (!landed && SystemClock.uptimeMillis() < awaitingUntil) return
            awaitingSeekTo = null
        }
        val difference = positionMs - predicted
        val holds = if (difference < 0) -difference < REGRESSION_TOLERANCE_MS else difference < DRIFT_TOLERANCE_MS
        anchorPositionMs = if (holds) predicted else positionMs
        anchorAt = SystemClock.uptimeMillis()
        if (!holds) endGesture()
        invalidate()
    }

    /**
     * Playback started or stopped, without saying anything about where it is: a player asked the
     * moment after a seek answers with where it *was*, and taking that as a position is what drags
     * the waveform back to where a scrub began.
     */
    fun setPlaying(playing: Boolean) {
        anchorPositionMs = positionMs
        anchorAt = SystemClock.uptimeMillis()
        this.playing = playing
        invalidate()
    }

    /** A seek somebody asked for, which is where playback really is whatever it last reported. */
    fun syncTo(positionMs: Int, playing: Boolean) {
        this.playing = playing
        if (dragging || flinging) return
        anchorPositionMs = clamped(positionMs)
        anchorAt = SystemClock.uptimeMillis()
        // A player answers with where it still is for a moment after being seeked; believing that
        // would drag the waveform back to where the scrub started.
        awaitingSeekTo = anchorPositionMs
        awaitingUntil = anchorAt + SEEK_SETTLE_MS
        endGesture()
        invalidate()
    }

    private fun endGesture() {
        dragging = false
        flinging = false
        scroller.forceFinished(true)
    }

    fun clear() {
        levels = FloatArray(0)
        syncTo(0, playing = false)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (knownDurationMs <= 0) return false
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                val interrupted = flinging
                // Reading the position before the throw is dropped: where it had got to is where
                // the clip now is, and losing it is what snapped the waveform back.
                dragPositionMs = positionMs
                scroller.forceFinished(true)
                flinging = false
                awaitingSeekTo = null
                if (interrupted) {
                    anchorPositionMs = dragPositionMs
                    anchorAt = SystemClock.uptimeMillis()
                    onSeek?.invoke(dragPositionMs, SeekPhase.INTERRUPTED)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    if (abs(event.x - downX) < touchSlop || abs(event.x - downX) < abs(event.y - downY)) return true
                    dragging = true
                    lastX = event.x
                }
                // Dragging left runs forwards, as pulling a tape past the head does.
                moveBy(lastX - event.x)
                lastX = event.x
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging) {
                    dragging = false
                    tracker.computeCurrentVelocity(1_000, configuration.scaledMaximumFlingVelocity.toFloat())
                    startFling(-tracker.xVelocity)
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                    onTap?.invoke()
                }
                releaseTracker()
                invalidate()
                return true
            }
        }
        return false
    }

    private fun moveBy(pixels: Float) {
        dragPositionMs = clamped((dragPositionMs + pixels / pixelsPerMs).roundToInt())
        onSeek?.invoke(dragPositionMs, SeekPhase.MOVING)
        invalidate()
    }

    /** The throw keeps travelling after the finger, decelerating as a scrolling list does. */
    private fun startFling(velocityX: Float) {
        if (abs(velocityX) < configuration.scaledMinimumFlingVelocity) {
            settle()
            return
        }
        flinging = true
        flingStartMs = dragPositionMs
        scroller.fling(0, 0, velocityX.toInt(), 0, -FLING_RANGE_PX, FLING_RANGE_PX, 0, 0)
        ViewCompat.postInvalidateOnAnimation(this)
    }

    private fun advanceFling() {
        if (!scroller.computeScrollOffset()) {
            flinging = false
            settle()
            return
        }
        val moved = clamped((flingStartMs + scroller.currX / pixelsPerMs).roundToInt())
        if (moved != dragPositionMs) {
            dragPositionMs = moved
            onSeek?.invoke(dragPositionMs, SeekPhase.MOVING)
        }
        // Running off either end is the throw finished, not a wall to sit against.
        if (moved == lowerBound || moved == upperBound) {
            scroller.forceFinished(true)
            flinging = false
            settle()
        }
    }

    private fun settle() {
        anchorPositionMs = dragPositionMs
        anchorAt = SystemClock.uptimeMillis()
        onSeek?.invoke(dragPositionMs, SeekPhase.SETTLED)
        invalidate()
    }

    private fun releaseTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        releaseTracker()
        scroller.forceFinished(true)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (flinging) advanceFling()
        val bars = levels
        val centreX = width / 2f
        val centreY = height / 2f
        val maximumHalf = minOf(maximumBarHalfHeight, height / 2f - dp(4f))
        val group = slicesPerBar
        // Bars are indexed by time, not by how much of the file has been read, so a waveform still
        // being decoded draws in the right place rather than stretching as it grows.
        val playedBars = positionMs.toFloat() / msPerBar
        val firstVisible = (playedBars - centreX / barStep).toInt()
        val lastVisible = ceil(playedBars + centreX / barStep).toInt()
        // The whole clip's worth of bars, not just the part read so far: what has yet to be decoded
        // draws flat, so the waveform fills in rather than growing out of nothing. With nothing read
        // and no length to go by, the band is filled flat rather than left blank — a bare strip
        // reads as broken, and a file that never says how long it is would leave one for good.
        val barCount = if (knownDurationMs > 0) ceil(knownDurationMs.toFloat() / msPerBar).toInt() else lastVisible + 1

        val firstKept = rangeStartMs / msPerBar
        val lastKept = if (rangeEndMs == Int.MAX_VALUE) barCount - 1 else rangeEndMs / msPerBar
        for (index in max(firstVisible, max(firstKept, 0))..minOf(lastVisible, minOf(lastKept, barCount - 1))) {
            var peak = 0f
            for (slice in index * group until minOf((index + 1) * group, bars.size)) {
                peak = max(peak, bars[slice])
            }
            val x = centreX + (index - playedBars) * barStep
            val half = max(peak * maximumHalf, dp(1f))
            canvas.drawLine(x, centreY - half, x, centreY + half, if (x <= centreX) playedPaint else pendingPaint)
        }
        if ((playing && !dragging) || flinging) ViewCompat.postInvalidateOnAnimation(this)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    companion object {
        /** How much sound the view holds at once, which is also how far one swipe travels. */
        private const val VISIBLE_WINDOW_MS = 8_000

        /** Past this a report has really moved on, rather than being the noise of asking. */
        private const val DRIFT_TOLERANCE_MS = 400

        /** A sink starting up walks its position backwards by up to about this much. */
        private const val REGRESSION_TOLERANCE_MS = 1_500

        /** How long a player is given to answer from where it was seeked to. */
        private const val SEEK_SETTLE_MS = 2_000

        private const val FLING_RANGE_PX = 1_000_000
    }
}
