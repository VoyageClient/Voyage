/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView that clips its content to rounded corners on pre-Lollipop, where clipToOutline is
 * unavailable. On Lollipop+ it behaves as a plain ImageView and callers use clipToOutline instead
 * (which is anti-aliased). Clipping covers animated drawables (GIF / penfeizhou WebP / APNG) that
 * Glide's Bitmap-only RoundedCorners transform can't round.
 */
class RoundedCornerImageView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val preLollipop = Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
    private val radii = FloatArray(8)
    private val clipPath = Path()
    private val bounds = RectF()
    private var hasRadius = false

    fun setCornerRadii(topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float) {
        radii[0] = topLeft; radii[1] = topLeft
        radii[2] = topRight; radii[3] = topRight
        radii[4] = bottomRight; radii[5] = bottomRight
        radii[6] = bottomLeft; radii[7] = bottomLeft
        hasRadius = topLeft > 0f || topRight > 0f || bottomRight > 0f || bottomLeft > 0f
        updatePath()
        invalidate()
    }

    private fun updatePath() {
        clipPath.reset()
        if (hasRadius && width > 0 && height > 0) {
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            clipPath.addRoundRect(bounds, radii, Path.Direction.CW)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updatePath()
    }

    override fun draw(canvas: Canvas) {
        if (preLollipop && hasRadius && !clipPath.isEmpty) {
            val save = canvas.save()
            canvas.clipPath(clipPath)
            super.draw(canvas)
            canvas.restoreToCount(save)
        } else {
            super.draw(canvas)
        }
    }
}
