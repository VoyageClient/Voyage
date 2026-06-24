/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.amulyakhare.textdrawable.TextDrawable
import com.bumptech.glide.request.target.DrawableImageViewTarget

/**
 * A Glide target that clips its drawable to a rounded rectangle / circle for any content that
 * Glide's bitmap transformations (CircleCrop / RoundedCorners) can't handle — i.e. animated
 * drawables and placeholders. This replaces `View.clipToOutline`, which is API 21+ only, so avatars
 * and thumbnails stay correctly shaped down to KitKat.
 *
 * Already-shaped [BitmapDrawable]s (the output of Glide's transforms) are passed through untouched
 * so the common static-image path keeps its efficient pre-rounded bitmap.
 */
class ClippedDrawableImageViewTarget(
        view: ImageView,
        private val cornerPercent: Float,
        private val oval: Boolean,
) : DrawableImageViewTarget(view) {

    private fun clip(drawable: Drawable?): Drawable? = when (drawable) {
        // Already-shaped content passes through untouched: BitmapDrawables are shaped by Glide's
        // transforms, and TextDrawable placeholders shape themselves. Only animated drawables
        // (GIF / WebP / APNG) actually need runtime clipping here.
        null, is BitmapDrawable, is TextDrawable -> drawable
        else -> RoundedClipDrawable(drawable, cornerPercent, oval)
    }

    override fun setResource(resource: Drawable?) = super.setResource(clip(resource))

    override fun onLoadStarted(placeholder: Drawable?) = super.onLoadStarted(clip(placeholder))

    override fun onLoadFailed(errorDrawable: Drawable?) = super.onLoadFailed(clip(errorDrawable))

    override fun onLoadCleared(placeholder: Drawable?) = super.onLoadCleared(clip(placeholder))
}
