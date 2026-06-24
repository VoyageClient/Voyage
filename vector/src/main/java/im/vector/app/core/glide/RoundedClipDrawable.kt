/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable

/**
 * Wraps a [Drawable] and clips it to a rounded rectangle (or circle) — a cross-version replacement
 * for `View.clipToOutline`, which is API 21+ only. Works for any content, including animated
 * drawables (animated WebP / APNG / GIF), by masking each frame.
 *
 * Masking is done with an anti-aliased [PorterDuff.Mode.DST_IN] pass inside a layer, so the edges
 * stay smooth on KitKat too.
 *
 * @param cornerPercent corner radius as a fraction of the shorter side, so the rounding looks the
 *   same at any avatar size; ignored when [oval] is true.
 * @param oval when true the content is clipped to an oval/circle filling the bounds.
 */
class RoundedClipDrawable(
        private val wrapped: Drawable,
        private val cornerPercent: Float,
        private val oval: Boolean,
) : Drawable(), Drawable.Callback, Animatable {

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val maskPath = Path()
    private var maskDirty = true

    init {
        wrapped.callback = this
    }

    private fun rebuildMask() {
        maskPath.reset()
        val rectF = RectF(bounds)
        if (oval) {
            maskPath.addOval(rectF, Path.Direction.CW)
        } else {
            val radius = minOf(rectF.width(), rectF.height()) * cornerPercent
            maskPath.addRoundRect(rectF, radius, radius, Path.Direction.CW)
        }
        maskDirty = false
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        if (maskDirty) rebuildMask()
        // The 2-arg saveLayer is API 21+; the flagged overload works on KitKat.
        @Suppress("DEPRECATION")
        val saved = canvas.saveLayer(RectF(bounds), null, Canvas.ALL_SAVE_FLAG)
        wrapped.draw(canvas)
        canvas.drawPath(maskPath, maskPaint)
        canvas.restoreToCount(saved)
    }

    override fun onBoundsChange(bounds: Rect) {
        wrapped.bounds = bounds
        maskDirty = true
    }

    override fun setAlpha(alpha: Int) {
        wrapped.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        wrapped.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = wrapped.intrinsicWidth

    override fun getIntrinsicHeight(): Int = wrapped.intrinsicHeight

    // --- Drawable.Callback: forward the wrapped (animated) drawable's invalidations to our host view.
    override fun invalidateDrawable(who: Drawable) = invalidateSelf()
    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)
    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)

    // --- Animatable: keep animated content playing.
    override fun start() {
        (wrapped as? Animatable)?.start()
    }

    override fun stop() {
        (wrapped as? Animatable)?.stop()
    }

    override fun isRunning(): Boolean = (wrapped as? Animatable)?.isRunning ?: false
}
