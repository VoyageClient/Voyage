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
import im.vector.app.features.home.avatar.AvatarShapes
import im.vector.app.features.settings.AvatarShape

/**
 * Wraps a [Drawable] and clips it to an avatar shape — a cross-version replacement for
 * `View.clipToOutline`, which is API 21+ only and cannot express a polygon at all before API 30.
 * Works for any content, including animated drawables (animated WebP / APNG / GIF), by masking each
 * frame.
 *
 * Masking is done with an anti-aliased [PorterDuff.Mode.DST_OUT] pass inside a layer, so the edges
 * stay smooth on KitKat too.
 */
class ShapeClipDrawable(
        private val wrapped: Drawable,
        private val shape: AvatarShape,
) : Drawable(), Drawable.Callback, Animatable {

    // DST_OUT over the *inverse* shape: a PorterDuff mode only blends the pixels the source geometry
    // actually covers, so filling the shape itself with DST_IN leaves everything outside it untouched —
    // i.e. no clipping at all. The region to erase is the one outside.
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val maskPath = Path()
    private var maskDirty = true

    init {
        wrapped.callback = this
    }

    private fun rebuildMask() {
        AvatarShapes.path(shape, RectF(bounds), maskPath)
        // The builder's reset() drops the fill type, so set it after.
        maskPath.fillType = Path.FillType.INVERSE_WINDING
        maskDirty = false
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        // A square fills its bounds, so there is nothing to erase and no reason to pay for a layer.
        if (shape == AvatarShape.SQUARE) {
            wrapped.draw(canvas)
            return
        }
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
