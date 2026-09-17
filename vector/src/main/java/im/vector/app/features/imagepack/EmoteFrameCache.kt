/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Small pictures of custom emotes and stickers, keyed by `mxc://` url.
 *
 * A picker cell and the sent message load the same image through different Glide keys (the cells
 * downsample to the grid), so picking one warms nothing for the message that follows: the emote span
 * drew "❓" and the sticker drew its loading fill for as long as the fresh decode took. What a cell
 * drew is kept here instead, and both draw it at once while the real decode runs.
 */
object EmoteFrameCache {

    private const val MAX_FRAME_PX = 128

    // Sized from the heap: this is what lets a picker grid repaint a cell the moment it scrolls back,
    // the way the bundled twemoji sprites do, so it has to hold a grid's worth of emotes at once.
    private val cacheKb = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceIn(4_096, 40_960)

    private val frames = object : LruCache<String, Bitmap>(cacheKb) {
        override fun sizeOf(key: String, value: Bitmap) = (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun get(mxcUrl: String?): Bitmap? = mxcUrl?.let { frames.get(it) }?.takeIf { !it.isRecycled }

    fun put(mxcUrl: String?, bitmap: Bitmap) {
        mxcUrl ?: return
        if (bitmap.isRecycled) return
        // Glide owns the bitmaps it hands out and returns them to its pool the moment the view is
        // cleared — keeping one by reference left a recycled bitmap here (and would have risked
        // drawing whatever Glide reused those bytes for), so what is kept is a copy of our own.
        frames.put(mxcUrl, bitmap.ownCopy() ?: return)
    }

    private fun Bitmap.ownCopy(): Bitmap? = runCatching {
        val scale = min(1f, MAX_FRAME_PX.toFloat() / max(width, height))
        val copy = if (scale < 1f) {
            Bitmap.createScaledBitmap(this, (width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1), true)
        } else {
            this
        }
        // createScaledBitmap hands back the source itself when it needs no work doing.
        if (copy === this) copy(Bitmap.Config.ARGB_8888, false) else copy
    }.getOrNull()

    /**
     * Keeps what [imageView] is drawing for [mxcUrl]. Called when a picker cell is *chosen* rather than
     * when it loads: capturing every cell copied a bitmap per emote (and drew a frame of each animated
     * one on the main thread), which made a grid of hundreds of them crawl. The one the user picked is
     * the only one about to be rendered somewhere else.
     */
    fun captureFrom(imageView: ImageView, mxcUrl: String?) {
        if (mxcUrl == null || get(mxcUrl) != null) return
        snapshot(imageView.drawable)?.let { put(mxcUrl, it) }
    }

    /** A copy of what [drawable] currently draws, bounded to [MAX_FRAME_PX]. */
    fun snapshot(drawable: Drawable?): Bitmap? {
        drawable ?: return null
        (drawable as? BitmapDrawable)?.bitmap?.takeIf { !it.isRecycled }?.let { return it }
        val intrinsicW = drawable.intrinsicWidth.takeIf { it > 0 } ?: return null
        val intrinsicH = drawable.intrinsicHeight.takeIf { it > 0 } ?: return null
        val scale = min(1f, MAX_FRAME_PX.toFloat() / max(intrinsicW, intrinsicH))
        val width = (intrinsicW * scale).roundToInt().coerceAtLeast(1)
        val height = (intrinsicH * scale).roundToInt().coerceAtLeast(1)
        return runCatching {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val previousBounds = drawable.copyBounds()
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            drawable.bounds = previousBounds
            bitmap
        }.getOrNull()
    }
}
