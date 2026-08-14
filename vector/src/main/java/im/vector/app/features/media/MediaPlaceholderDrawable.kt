/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import im.vector.app.R
import im.vector.app.features.themes.ThemeUtils
import kotlin.math.cos

/**
 * Stands in for media from the moment it is asked for until it arrives, covering both waiting and
 * failed. Deliberately one drawable rather than two swapped ones: the states share a fill and differ
 * only by a scrim and a glyph, so moving between them animates this object's own parameters.
 * Crossfading two translucent drawables instead reads as a flash, and a fresh pulse would restart at
 * its brightest rather than continuing from whatever is on screen.
 */
class MediaPlaceholderDrawable(
        context: Context,
        val blurHash: BlurHashDrawable? = null,
        private val showGlyph: Boolean = true,
) : Drawable(), Drawable.Callback, Runnable {

    init {
        // The blurhash decodes off the main thread and invalidates itself when the bitmap lands.
        // Without owning its callback that invalidation goes nowhere and the backdrop never paints.
        blurHash?.callback = this
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_quinary)
    }

    // A blurhash can be any colour, including one the glyph would vanish against.
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(SCRIM_ALPHA, 0, 0, 0)
    }

    private val icon: Drawable? = AppCompatResources.getDrawable(context, R.drawable.ic_media_failed)
            ?.mutate()
            ?.let { DrawableCompat.wrap(it) }
            ?.also { DrawableCompat.setTint(it, ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary)) }

    private val density = context.resources.displayMetrics.density
    private val cornerRadius = CORNER_RADIUS_DP * density
    private val handler = Handler(Looper.getMainLooper())

    private var running = false
    private var failed = false

    /**
     * Media of our own whose bytes are still being uploaded has no download that can time out, and the
     * wait lasts as long as the upload does — so nothing here is allowed to call it failed.
     */
    var boundedWait = true
        set(value) {
            field = value
            if (!value && failed) setFailed(false)
        }

    /** Where the failure fade currently sits, 0 waiting .. 1 failed. Eased from wherever it was. */
    private var failProgress = 0f
    private var progressAtChange = 0f
    private var changedAtMs = 0L
    private var waitingSinceMs = SystemClock.uptimeMillis()

    fun setFailed(value: Boolean) {
        if (value && !boundedWait) return
        if (failed == value) return
        failed = value
        // Anchor the ease to the value on screen right now, so reversing mid-fade continues from
        // there instead of restarting from either end.
        progressAtChange = failProgress
        changedAtMs = SystemClock.uptimeMillis()
        waitingSinceMs = if (value) 0L else changedAtMs
        invalidateSelf()
        if (isVisible) start()
    }

    fun isFailed(): Boolean = failed

    override fun draw(canvas: Canvas) {
        val rect = RectF(bounds)
        val target = if (failed) 1f else 0f
        failProgress = if (changedAtMs == 0L) {
            target
        } else {
            val t = ((SystemClock.uptimeMillis() - changedAtMs).toFloat() / FADE_MS).coerceIn(0f, 1f)
            progressAtChange + (target - progressAtChange) * t
        }

        // The pulse rides on absolute time, never on when this state began, so it is phase-continuous
        // across every transition — the fill can only ever change by the amount one frame's worth of
        // breathing moves it. Its depth fades out as the failure settles rather than stopping dead.
        val phase = (SystemClock.uptimeMillis() % PULSE_PERIOD_MS).toDouble() / PULSE_PERIOD_MS * 2.0 * Math.PI
        val breathe = (cos(phase) + 1.0) / 2.0
        // Alpha fades a drawable toward whatever is behind it, so a deep swing suits the flat fill —
        // where dimming is the whole signal — but would fade a blurhash into the background instead
        // of breathing it. The hash therefore pulses shallowly and settles at full strength.
        val minAlpha = if (blurHash != null) BLUR_PULSE_MIN_ALPHA else FILL_PULSE_MIN_ALPHA
        val maxAlpha = if (blurHash != null) 1.0 else FILL_PULSE_MAX_ALPHA
        val amplitude = (maxAlpha - minAlpha) * (1f - failProgress)
        val fillAlpha = minAlpha + (maxAlpha - minAlpha) * failProgress + amplitude * breathe

        val alpha = (255 * fillAlpha * externalAlpha / 255).toInt().coerceIn(0, 255)
        if (blurHash != null) {
            blurHash.setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
            blurHash.alpha = alpha
            blurHash.draw(canvas)
        } else {
            fillPaint.alpha = alpha
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
        }

        if (showGlyph && failProgress > 0f) {
            scrimPaint.alpha = (SCRIM_ALPHA * failProgress * externalAlpha / 255).toInt()
            canvas.drawRect(rect, scrimPaint)

            icon?.let {
                val size = (minOf(bounds.width(), bounds.height()) * ICON_FRACTION)
                        .coerceIn(MIN_ICON_DP * density, MAX_ICON_DP * density)
                        .toInt()
                val left = bounds.left + ((bounds.width() - size) / 2)
                val top = bounds.top + ((bounds.height() - size) / 2)
                it.setBounds(left, top, left + size, top + size)
                it.alpha = (255 * failProgress * externalAlpha / 255).toInt()
                it.draw(canvas)
            }
        }
    }

    override fun run() {
        if (!running) return
        // Not every loader reports back. Requests served by Glide's own HTTP stack, rather than the
        // fetcher that has its own timeout, can hang with no callback at all — so waiting is bounded
        // here, where it covers whoever is doing the loading.
        if (boundedWait && !failed && waitingSinceMs != 0L && SystemClock.uptimeMillis() - waitingSinceMs > WAIT_TIMEOUT_MS) {
            setFailed(true)
        }
        invalidateSelf()
        // Settled failures have nothing left to animate; waiting ones breathe indefinitely.
        if (failed && failProgress >= 1f) {
            running = false
        } else {
            handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    private fun start() {
        if (running) return
        running = true
        handler.post(this)
    }

    private fun stop() {
        running = false
        handler.removeCallbacks(this)
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) start() else stop()
        return changed
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (isVisible) start()
    }

    // The cross-fade out of this placeholder drives us through setAlpha, so the pulse has to be
    // scaled by it rather than painting at its own strength until it is dropped.
    private var externalAlpha = 255

    override fun setAlpha(alpha: Int) {
        externalAlpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        icon?.colorFilter = colorFilter
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun invalidateDrawable(who: Drawable) = invalidateSelf()

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)

    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)

    companion object {
        private const val CORNER_RADIUS_DP = 8f
        private const val ICON_FRACTION = 0.34f
        private const val MIN_ICON_DP = 16f
        private const val MAX_ICON_DP = 48f
        private const val SCRIM_ALPHA = 0x66
        private const val FRAME_INTERVAL_MS = 16L
        private const val FADE_MS = 320f
        private const val PULSE_PERIOD_MS = 1300L
        private const val FILL_PULSE_MIN_ALPHA = 0.32
        private const val FILL_PULSE_MAX_ALPHA = 0.72

        // Shallow, matching what the blurhash placeholder always did on its own.
        private const val BLUR_PULSE_MIN_ALPHA = 0.65
        private const val WAIT_TIMEOUT_MS = 30_000L
    }
}
