/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar.effect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import java.util.WeakHashMap

/** Turns whatever Glide loaded into an avatar drawn as an animated shape. */
object AvatarEffectDrawables {

    /**
     * @param host the view the avatar lands in, one of the sources for the size to render at.
     * @param requestedPx the size the caller is decoding this avatar at, which is the best answer
     *   when it is known: a view is usually unmeasured at the point its placeholder is wrapped, and
     *   guessing then only to correct on a later bind renders every avatar twice at two sizes.
     */
    fun wrap(drawable: Drawable?, effect: AvatarEffect, host: View?, requestedPx: Int? = null): Drawable? {
        drawable ?: return null
        val sizePx = sizeFor(drawable, host, requestedPx)
        if (sizePx < effect.minSizePx) {
            return drawable
        }
        val texture = textureOf(drawable, sizePx) ?: run {
            return drawable
        }

        // A fresh drawable has no frame yet, so swapping the texture under the one the view already
        // has keeps both the frame on screen and the animation's place across a reload.
        val previous = host?.let { lastByHost[it] }
        if (previous != null && previous.effect == effect && previous.sizePx == sizePx) {
            previous.swapTexture(texture)
            previous.setAnimatedSource(animatedSourceOf(drawable))
            return previous
        }

        val next = AnimatedAvatarDrawable(texture, effect, sizePx)
        previous?.let { next.adoptFrameFrom(it) }
        next.setAnimatedSource(animatedSourceOf(drawable))
        host?.let { lastByHost[it] = next }
        return next
    }

    /** A still of the shape, for the slots that never animate: pills, shortcut icons, previews. */
    fun still(bitmap: Bitmap, effect: AvatarEffect, sizePx: Int): Bitmap =
            AvatarEffectRenderer.renderStill(effect, square(bitmap, sizePx), sizePx)

    /**
     * A live preview for the settings picker.
     *
     * Rendered smaller than the tile it fills, unlike a real avatar: the picker animates every shape
     * at once, so its whole grid costs what one avatar would at full size, and the tile scales the
     * frame up. A thumbnail of a tumbling shape does not need the resolution.
     */
    fun animatedPreview(source: Drawable, effect: AvatarEffect, tilePx: Int): Drawable {
        val sizePx = minOf(tilePx, PREVIEW_SIZE_PX)
        val texture = textureOf(source, sizePx) ?: return source
        return AnimatedAvatarDrawable(texture, effect, sizePx, exemptFromCap = true)
    }

    /** The most trustworthy size available, since a view is often unmeasured when this runs. */
    private fun sizeFor(drawable: Drawable, host: View?, requestedPx: Int?): Int {
        val candidates = intArrayOf(
                requestedPx ?: 0,
                maxOf(host?.width ?: 0, host?.height ?: 0),
                // Fixed layout sizes, for a view that has not been measured yet.
                maxOf(host?.layoutParams?.width ?: 0, host?.layoutParams?.height ?: 0),
                maxOf(drawable.intrinsicWidth, drawable.intrinsicHeight),
        )
        return quantize(candidates.firstOrNull { it > 0 } ?: DEFAULT_SIZE_PX)
    }

    // Bucketed so the bitmap pool has few sizes to keep. Rounding to the nearest bucket rather than
    // up matters: a 56dp avatar at 3x is 168px, and taking that to 192 is a third more pixels to
    // rasterise every frame, where 160 is a 5% upscale nobody can see.
    private fun quantize(px: Int) =
            ((px + SIZE_BUCKET_PX / 2) / SIZE_BUCKET_PX * SIZE_BUCKET_PX).coerceIn(MIN_SIZE_PX, MAX_SIZE_PX)

    /** An avatar that moves on its own, which the shape can keep taking its texture from. */
    private fun animatedSourceOf(drawable: Drawable) = drawable.takeIf { it is Animatable }

    // Glide recycles the loaded bitmap back into its pool when the request is cleared, while the
    // drawable and the renderer may still be reading it — so the effect always owns its own copy.
    private fun textureOf(drawable: Drawable, sizePx: Int): Bitmap? = when (drawable) {
        is BitmapDrawable -> drawable.bitmap?.let { square(it, sizePx) }
        else -> rasterize(drawable, sizePx)
    }

    private fun square(source: Bitmap, sizePx: Int): Bitmap =
            if (source.width == sizePx && source.height == sizePx) source.copy(Bitmap.Config.ARGB_8888, false)
            else Bitmap.createScaledBitmap(source, sizePx, sizePx, true)

    // The first frame, which is also the still an animated avatar falls back to when autoplay is off.
    private fun rasterize(drawable: Drawable, sizePx: Int): Bitmap? {
        if (sizePx <= 0) return null
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bitmap
    }

    // Weakly keyed, so a view that goes away takes its shape with it.
    private val lastByHost = WeakHashMap<View, AnimatedAvatarDrawable>()

    private const val DEFAULT_SIZE_PX = 96
    private const val PREVIEW_SIZE_PX = 80
    private const val SIZE_BUCKET_PX = 32
    private const val MIN_SIZE_PX = 48
    private const val MAX_SIZE_PX = 256
}
