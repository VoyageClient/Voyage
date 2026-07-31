/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.drawable.Drawable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which variant actually served a media, so later binds ask for that one first. Otherwise
 * every bind re-runs the same losing cache probe, and a probe that misses memory resolves
 * asynchronously — long enough for Glide to put the placeholder up, which reads as a flicker.
 */
@Singleton
class ThumbnailVariants @Inject constructor() {

    private val lastServed = object : LinkedHashMap<String, String>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > MAX_ENTRIES
    }

    @Synchronized
    fun servedBy(mediaUrl: String): String? = lastServed[mediaUrl]

    @Synchronized
    fun remember(mediaUrl: String, variantUrl: String) {
        lastServed[mediaUrl] = variantUrl
    }

    companion object {
        private const val MAX_ENTRIES = 512
        private const val INITIAL_CAPACITY = 128
        private const val LOAD_FACTOR = 0.75f
    }
}

/** Records the variant a load was answered with, for [ThumbnailVariants] to order the next one by. */
class RememberServedVariant(
        private val variants: ThumbnailVariants,
        private val mediaUrl: String,
) : RequestListener<Drawable> {

    override fun onResourceReady(
            resource: Drawable,
            model: Any,
            target: Target<Drawable>?,
            dataSource: DataSource,
            isFirstResource: Boolean,
    ): Boolean {
        (model as? String)?.let { variants.remember(mediaUrl, it) }
        return false
    }

    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean) = false
}
