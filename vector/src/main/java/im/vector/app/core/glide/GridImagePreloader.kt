/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import im.vector.app.features.imagepack.EmoteFrameCache
import org.matrix.android.sdk.api.debug.DebugLog

/**
 * Decodes a picker grid's cells before they are scrolled to.
 *
 * A pack grid is hundreds of small images, and a cell only starts loading when it binds — so a fling
 * queues hundreds of decodes at once and the grid fills in behind the scroll rather than under the
 * finger. Warming them in order, as the grid is populated, means the cells are in Glide's memory cache
 * by the time they bind.
 *
 * The request must match what the cell asks for exactly, or it lands under a different cache key and
 * the work is wasted.
 */
object GridImagePreloader {

    // Queued a batch at a time: preload() has to run on the main thread, and hundreds of them in one
    // pass costs a frame of its own.
    private const val BATCH_SIZE = 16

    // Bounded by what the frame cache can hold anyway; past that the LRU just churns.
    private const val MAX_IMAGES = 600

    private val handler = Handler(Looper.getMainLooper())

    // Keyed per grid: the pickers coexist (the composer's keyboard and the sticker sheet), and cancelling
    // on every start meant whichever opened second stopped the first one's warm halfway through.
    private val running = HashMap<String, Runnable>()

    /**
     * @param animated whether cells draw the animation (stickers) or a still frame (emotes).
     * @param keepFrameFor the mxc url a warmed still frame belongs to, so it lands in the cache a cell
     * reads synchronously — warming Glide alone still left the first pass over a pack going through it,
     * at four times the cost of a cell served from memory.
     */
    fun warm(
            key: String,
            context: Context,
            urls: List<String>,
            size: Int,
            animated: Boolean,
            keepFrameFor: ((String) -> String?)? = null,
    ) {
        cancel(key)
        val pending = urls.take(MAX_IMAGES).toMutableList()
        val startedAt = android.os.SystemClock.uptimeMillis()
        var warmed = 0
        DebugLog.i { "MEDIADBG preloader starting on ${pending.size} images of size $size animated=$animated" }
        val batch = object : Runnable {
            override fun run() {
                // The grid can go away between batches, and Glide refuses a load for a dead activity.
                if (context.isGone()) {
                    running.remove(key)
                    return
                }
                var queued = 0
                while (pending.isNotEmpty() && queued < BATCH_SIZE) {
                    val url = pending.removeAt(0)
                    if (animated) {
                        GlideApp.with(context).load(url).override(size, size).optionalFitCenter()
                                .diskCacheStrategy(DiskCacheStrategy.RESOURCE).preload(size, size)
                    } else {
                        val mxcUrl = keepFrameFor?.invoke(url)
                        GlideApp.with(context).asBitmap().load(url).override(size, size)
                                // ALL: the cell may want the file itself (an animated sticker decodes
                                // its animation from it), not only this still frame.
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .let { request ->
                                    if (mxcUrl == null) request else request.addListener(FrameKeeper(mxcUrl))
                                }
                                .preload(size, size)
                    }
                    queued++
                }
                warmed += queued
                if (pending.isNotEmpty()) {
                    handler.post(this)
                } else {
                    // Queued, not decoded: the decodes land later, on Glide's threads.
                    DebugLog.i { "MEDIADBG preloader queued $warmed images of size $size in ${android.os.SystemClock.uptimeMillis() - startedAt}ms" }
                    running.remove(key)
                }
            }
        }
        running[key] = batch
        handler.post(batch)
    }

    private class FrameKeeper(private val mxcUrl: String) : RequestListener<Bitmap> {
        override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>, isFirstResource: Boolean) = false

        override fun onResourceReady(
                resource: Bitmap,
                model: Any,
                target: Target<Bitmap>?,
                dataSource: DataSource,
                isFirstResource: Boolean,
        ): Boolean {
            EmoteFrameCache.put(mxcUrl, resource)
            return false
        }
    }

    private fun Context.isGone(): Boolean {
        val activity = generateSequence(this) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
        return activity != null && (activity.isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed))
    }

    /** Stops warming a grid that is gone; its queued decodes would only delay the next one's. */
    fun cancel(key: String) {
        running.remove(key)?.let { handler.removeCallbacks(it) }
    }
}
