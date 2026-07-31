/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import com.bumptech.glide.integration.webp.decoder.WebpDrawable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.github.penfeizhou.animation.FrameAnimationDrawable

/**
 * Plays animated content from its first frame: Glide's memory cache hands back the very drawable a
 * previous view was using, mid-animation.
 *
 * Content already playing is left alone — a second view binding it, as the open transition and the
 * viewer behind it do, would otherwise jerk it backwards, and GifDrawable refuses the call outright.
 */
fun Drawable.restartAnimation() {
    if (this !is Animatable || isRunning) return
    when (this) {
        // A drawable from Glide's memory cache shares its frame loader with every other view
        // showing the same content; the loader can still be running for a sibling view while this
        // drawable is stopped, and then refuses the rewind — fall back to joining the animation.
        is GifDrawable -> try {
            startFromFirstFrame()
        } catch (e: IllegalArgumentException) {
            start()
        }
        is WebpDrawable -> try {
            startFromFirstFrame()
        } catch (e: IllegalArgumentException) {
            start()
        }
        is FrameAnimationDrawable<*> -> {
            reset()
            start()
        }
        else -> start()
    }
}

/** Rewinds on every bind, including the ones Glide answers straight from its memory cache. */
object RestartAnimationListener : RequestListener<Drawable> {

    override fun onResourceReady(
            resource: Drawable,
            model: Any,
            target: Target<Drawable>?,
            dataSource: DataSource,
            isFirstResource: Boolean,
    ): Boolean {
        resource.restartAnimation()
        // The target still has to display it.
        return false
    }

    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean) = false
}
