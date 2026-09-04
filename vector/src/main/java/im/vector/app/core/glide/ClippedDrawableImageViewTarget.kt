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
import im.vector.app.core.ui.PerformanceMode
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.avatar.ShapedAvatarDrawable
import im.vector.app.features.home.avatar.effect.AnimatedAvatarDrawable
import im.vector.app.features.home.avatar.effect.AvatarEffectDrawables
import im.vector.app.features.settings.AvatarShape

/**
 * A Glide target that gives its drawable the avatar shape the user picked, for the content Glide's
 * bitmap transformations cannot shape themselves: animated drawables and placeholders.
 * `View.clipToOutline` covers circles and rounded squares from Lollipop up, and everything else
 * takes a masking drawable, so avatars stay correctly shaped down to Ice Cream Sandwich.
 *
 * An animated shape is different again: it replaces the picture with a rendering of it, so the
 * target wraps the loaded bitmap in an [AnimatedAvatarDrawable] and drives it with the view.
 *
 * Already-shaped [BitmapDrawable]s (the output of Glide's transforms) pass through untouched, so the
 * common static-image path keeps its efficient pre-shaped bitmap.
 *
 * @param animate false still gets animated content, since [thumbnailAttempts] may serve a cached
 *   animated variant once autoplay is off.
 * @param renderSizePx the size the caller is decoding at, for the animated shapes to render at.
 */
class ClippedDrawableImageViewTarget(
        view: ImageView,
        private val shape: AvatarShape,
        animate: Boolean = true,
        private val renderSizePx: Int? = null,
) : AnimatedContentImageViewTarget(view, animate) {

    // A square avatar has nothing to shape, and masking costs a saveLayer on every frame. An
    // animated shape brings its own silhouette, so masking it to a square would only clip it.
    private val shapes = shape != AvatarShape.SQUARE && !shape.isAnimated

    // ViewOutlineProvider can express a round rect and an oval, and nothing else until API 30, so
    // the polygons take the masking drawable at every API level.
    private val outlineCanExpress = shape == AvatarShape.CIRCLE || shape == AvatarShape.ROUNDED

    private var animatedShape: AnimatedAvatarDrawable? = null

    private fun clip(drawable: Drawable?): Drawable? {
        shape.effect?.let { effect ->
            // Glide follows a load by setting a null resource; the shape running at the time outlives
            // that and must not be stopped by it.
            val shaped = AvatarEffectDrawables.wrap(drawable, effect, view, renderSizePx) ?: return null
            val next = shaped as? AnimatedAvatarDrawable
            if (next !== animatedShape) {
                animatedShape?.stop()
                // Glide starts an Animatable resource itself, but what it inspects is the bitmap this
                // wraps, so the wrapper has to be started here. Performance mode leaves it stopped,
                // which keeps the shape and drops only the movement.
                animatedShape = next?.also { if (!PerformanceMode.enabled) it.start() }
            }
            return shaped
        }
        // Already-shaped content passes through untouched: BitmapDrawables are shaped by Glide's
        // transforms, and default-avatar placeholders shape themselves. Only animated drawables
        // (GIF / WebP / APNG) actually need runtime clipping here.
        val shapeNeeded = drawable != null && !drawable.isSelfShaped() && shapes
        if (outlineCanExpress && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            clipViewToShape(shapeNeeded)
            return drawable
        }
        return if (shapeNeeded) ShapeClipDrawable(drawable!!, shape) else drawable
    }

    // AnimatedImageDrawable hands its frames to the RenderThread, which composites them straight past
    // the drawable-level mask, so the view itself has to do the clipping wherever the platform can.
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun clipViewToShape(clip: Boolean) {
        view.outlineProvider = if (!clip) null else object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                if (shape == AvatarShape.CIRCLE) {
                    outline.setOval(0, 0, v.width, v.height)
                } else {
                    outline.setRoundRect(0, 0, v.width, v.height, minOf(v.width, v.height) * AvatarRenderer.ROUNDED_CORNER_PERCENT)
                }
            }
        }
        view.clipToOutline = clip
    }

    // The transition path bypasses setResource, where the clip is applied — drop the transition for
    // content that needs runtime clipping so animated avatars never draw unshaped mid-fade.
    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
        val needsClip = shape.isAnimated || (!resource.isSelfShaped() && shapes)
        super.onResourceReady(resource, if (needsClip) null else transition)
    }

    // The view going off and back on screen drives the shape, but the autoplay preference the
    // superclass gates on does not: an animated shape is one the user chose, not media that moves
    // on its own.
    override fun onStart() {
        super.onStart()
        animatedShape?.let {
            it.start()
        }
    }

    override fun onStop() {
        super.onStop()
        animatedShape?.let {
            it.stop()
        }
    }

    override fun setResource(resource: Drawable?) = super.setResource(clip(resource))

    // Reloading an avatar that is already showing its shape — a room's toolbar after sending a
    // message, a timeline row rebinding — should not blink back to a placeholder and start over.
    override fun onLoadStarted(placeholder: Drawable?) {
        animatedShape?.let {
            if (it.isRunning) {
                return
            }
        }
        super.onLoadStarted(clip(placeholder))
    }

    override fun onLoadFailed(errorDrawable: Drawable?) = super.onLoadFailed(clip(errorDrawable))

    // Recycling is the one point where the shape genuinely belongs to a different avatar.
    override fun onLoadCleared(placeholder: Drawable?) {
        animatedShape?.let {
            it.stop()
            // The request is going away and Glide may recycle what it was drawing.
            it.release()
        }
        animatedShape = null
        super.onLoadCleared(clip(placeholder))
    }

    private fun Drawable.isSelfShaped() =
            this is BitmapDrawable || this is TextDrawable || this is ShapedAvatarDrawable || this is AnimatedAvatarDrawable
}
