/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import im.vector.app.features.settings.AvatarShape

/** A solid fill in an [AvatarShape]'s outline, where a `GradientDrawable` cannot describe the shape. */
class AvatarShapeBackgroundDrawable(
        private val shape: AvatarShape,
        @ColorInt color: Int,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val path = Path()
    private val boundsF = RectF()

    override fun onBoundsChange(bounds: Rect) {
        boundsF.set(bounds)
        AvatarShapes.path(shape, boundsF, path)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
