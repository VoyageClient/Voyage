/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

data class ThumbnailAttempt(val url: String, val cacheOnly: Boolean)

/**
 * The animated (`animated=true`) and still variants of a thumbnail are independently cached server
 * resources, so with autoplay off an already downloaded animated one is preferred — frozen on its
 * first frame, which is what the still variant holds — over fetching the still one too.
 *
 * [urlFor] is only called for the variants that can actually be reached.
 */
fun thumbnailAttempts(autoplay: Boolean, urlFor: (animated: Boolean) -> String?): List<ThumbnailAttempt>? {
    val animatedUrl = urlFor(true) ?: return null
    if (autoplay) return listOf(ThumbnailAttempt(animatedUrl, cacheOnly = false))
    val stillUrl = urlFor(false) ?: return null
    return listOf(
            ThumbnailAttempt(stillUrl, cacheOnly = true),
            ThumbnailAttempt(animatedUrl, cacheOnly = true),
            ThumbnailAttempt(stillUrl, cacheOnly = false),
    )
}

fun <T> chainAttempts(attempts: List<ThumbnailAttempt>, load: (ThumbnailAttempt) -> T, fallingBackTo: (T, T) -> T): T {
    return attempts.map(load).reduceRight(fallingBackTo)
}
