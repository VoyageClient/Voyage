/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the most recent attempt to draw a piece of media ended in failure — any failure, whether
 * the server refused it, the network dropped, or nothing here could decode the bytes. From the
 * reader's side those are one thing: the content did not appear.
 *
 * Deliberately not a blacklist. A homeserver having a bad day answers 4xx for media that is
 * perfectly fine, so every fresh bind re-attempts and [onAttempt] drops the previous verdict; a
 * failure lives only as long as the view that saw it.
 */
@Singleton
class FailedMediaTracker @Inject constructor() {

    private val failed = Collections.synchronizedSet(LinkedHashSet<String>())

    fun isFailed(url: String?): Boolean = url != null && failed.contains(url)

    /**
     * Cleared on success rather than when an attempt starts. Clearing it up front meant a rebind
     * mid-retry saw healthy media and dropped to the loading state, so jumping to a failed message
     * flashed the empty box before the glyph came back.
     */
    fun onLoadSucceeded(url: String?) {
        url ?: return
        failed.remove(url)
    }

    fun onLoadFailed(url: String?) {
        url ?: return
        synchronized(failed) {
            if (failed.size >= MAX_ENTRIES) {
                failed.iterator().takeIf { it.hasNext() }?.let {
                    it.next()
                    it.remove()
                }
            }
            failed.add(url)
        }
    }

    companion object {
        private const val MAX_ENTRIES = 512
    }
}
