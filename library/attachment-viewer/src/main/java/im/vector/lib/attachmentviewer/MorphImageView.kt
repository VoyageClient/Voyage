/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.lib.attachmentviewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import kotlin.math.max
import kotlin.math.min

/**
 * Transition image for the avatar shared-element morph. When [morphAvatarSizePx] > 0 it draws its drawable
 * with a matrix it computes itself in [onDraw], blending center-crop (at the avatar box) and fit-center (at
 * full screen) from the live bounds. Doing it in onDraw — the last step before drawing — makes it immune to
 * the shared-element framework resetting the scale type / draw matrix to the source avatar's mid-transition
 * (which otherwise flashed a tiny top-left image for a frame). With [morphAvatarSizePx] == 0 it is a plain
 * ImageView (used unchanged for the timeline media viewer).
 */
class MorphImageView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyle: Int = 0,
) : ImageView(context, attrs, defStyle) {

    // Pixel size of the square avatar box the morph starts from; 0 disables the morph.
    var morphAvatarSizePx = 0

    private val morphMatrix = Matrix()

    override fun onDraw(canvas: Canvas) {
        val drawable = drawable
        val avatarSize = morphAvatarSizePx
        val fullHeight = (parent as? View)?.height ?: 0
        val viewWidth = width
        val viewHeight = height
        if (avatarSize <= 0 || drawable == null || viewWidth <= 0 || viewHeight <= 0 ||
                fullHeight <= avatarSize || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
            super.onDraw(canvas)
            return
        }
        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()
        // Reach full fit just before the box reaches full screen so the last frames match the pager exactly.
        val fraction = ((viewHeight - avatarSize) / ((fullHeight - avatarSize) * FIT_COMPLETION)).coerceIn(0f, 1f)
        val vw = viewWidth.toFloat()
        val vh = viewHeight.toFloat()
        val fitScale = min(vw / drawableWidth, vh / drawableHeight)
        val fillScale = max(vw / drawableWidth, vh / drawableHeight)
        val scale = fillScale + (fitScale - fillScale) * fraction
        morphMatrix.setScale(scale, scale)
        morphMatrix.postTranslate((vw - drawableWidth * scale) / 2f, (vh - drawableHeight * scale) / 2f)
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        val saved = canvas.save()
        canvas.concat(morphMatrix)
        drawable.draw(canvas)
        canvas.restoreToCount(saved)
    }

    companion object {
        private const val FIT_COMPLETION = 0.95f
    }
}
