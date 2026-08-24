/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.Outline
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.annotation.RequiresApi
import com.amulyakhare.textdrawable.TextDrawable
import com.bumptech.glide.request.transition.Transition

/**
 * A Glide target that clips its drawable to a rounded rectangle / circle for any content that
 * Glide's bitmap transformations (CircleCrop / RoundedCorners) can't handle — i.e. animated
 * drawables and placeholders. `View.clipToOutline` does it from Lollipop up; below that the drawable
 * is wrapped in a masking one, so avatars stay correctly shaped down to Ice Cream Sandwich.
 *
 * Already-shaped [BitmapDrawable]s (the output of Glide's transforms) are passed through untouched
 * so the common static-image path keeps its efficient pre-rounded bitmap.
 *
 * @param animate false still gets animated content, since [thumbnailAttempts] may serve a cached
 *   animated variant once autoplay is off.
 */
class ClippedDrawableImageViewTarget(
        view: ImageView,
        private val cornerPercent: Float,
        private val oval: Boolean,
        animate: Boolean = true,
) : AnimatedContentImageViewTarget(view, animate) {

    private fun clip(drawable: Drawable?): Drawable? {
        // Already-shaped content passes through untouched: BitmapDrawables are shaped by Glide's
        // transforms, and TextDrawable placeholders shape themselves. Only animated drawables
        // (GIF / WebP / APNG) actually need runtime clipping here. A square avatar has nothing to
        // shape either, and masking costs a saveLayer on every frame.
        val shapeNeeded = drawable != null && drawable !is BitmapDrawable && drawable !is TextDrawable &&
                (oval || cornerPercent > 0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            clipViewToShape(shapeNeeded)
            return drawable
        }
        return if (shapeNeeded) RoundedClipDrawable(drawable!!, cornerPercent, oval) else drawable
    }

    // AnimatedImageDrawable hands its frames to the RenderThread, which composites them straight past
    // the drawable-level mask, so the view itself has to do the clipping wherever the platform can.
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun clipViewToShape(clip: Boolean) {
        view.outlineProvider = if (!clip) null else object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                if (oval) {
                    outline.setOval(0, 0, v.width, v.height)
                } else {
                    outline.setRoundRect(0, 0, v.width, v.height, minOf(v.width, v.height) * cornerPercent)
                }
            }
        }
        view.clipToOutline = clip
    }

    // The transition path bypasses setResource, where the clip is applied — drop the transition for
    // content that needs runtime clipping so animated avatars never draw unshaped mid-fade.
    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
        val needsClip = resource !is BitmapDrawable && resource !is TextDrawable && (oval || cornerPercent > 0f)
        super.onResourceReady(resource, if (needsClip) null else transition)
    }

    override fun setResource(resource: Drawable?) = super.setResource(clip(resource))

    override fun onLoadStarted(placeholder: Drawable?) = super.onLoadStarted(clip(placeholder))

    override fun onLoadFailed(errorDrawable: Drawable?) = super.onLoadFailed(clip(errorDrawable))

    override fun onLoadCleared(placeholder: Drawable?) = super.onLoadCleared(clip(placeholder))
}
