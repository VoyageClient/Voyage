/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.util.time.Clock
import javax.inject.Inject

private const val MAX_ENTRIES = 16
private const val TTL_MS = 10 * 60 * 1000L

// A homeserver which cannot preview a link, or a site which did not answer, must not be asked again on
// every send: that is what made sending slow whenever the preview could not be had.
private const val FAILURE_TTL_MS = 2 * 60 * 1000L

/**
 * Holds finished previews — page read and thumbnail already uploaded — so that sending a message does not
 * wait for the round trip a link typed moments ago has already paid for.
 *
 * Keyed by more than the url: a preview generated on the device is not the one the homeserver would give,
 * and the thumbnail of an encrypted room is a different (encrypted) upload from a clear one.
 */
@SessionScope
internal class UrlPreviewBundleCache @Inject constructor(
        private val clock: Clock,
) {

    data class Key(
            val url: String,
            val onDevice: Boolean,
            val encrypted: Boolean,
    )

    /** A finished answer, which may well be "this link has no preview". */
    class Known(val preview: JsonDict?)

    private class Entry(val preview: JsonDict?, val storedAt: Long, val ttl: Long)

    private val mutex = Mutex()
    private val entries = LinkedHashMap<Key, Entry>()
    private val inFlight = mutableMapOf<Key, CompletableDeferred<JsonDict?>>()

    /** What is already known about [key], without starting any work. Null when nothing is. */
    suspend fun peek(key: Key): Known? = mutex.withLock { valid(key)?.let { Known(it.preview) } }

    /**
     * The preview for [key], building it with [build] unless it is already known or already being built —
     * typing and then sending the same link must not fetch and upload it twice.
     */
    suspend fun getOrBuild(key: Key, build: suspend () -> JsonDict?): JsonDict? {
        val pending: CompletableDeferred<JsonDict?>
        val mine: Boolean
        mutex.withLock {
            valid(key)?.let { return it.preview }
            val existing = inFlight[key]
            mine = existing == null
            pending = existing ?: CompletableDeferred<JsonDict?>().also { inFlight[key] = it }
        }
        if (!mine) return pending.await()

        var built: JsonDict? = null
        try {
            built = build()
        } finally {
            mutex.withLock {
                inFlight.remove(key)
                // "No preview" is remembered too, but only briefly: long enough that a link which cannot be
                // previewed stops delaying every send, short enough that a passing failure heals itself.
                store(key, built, if (built == null) FAILURE_TTL_MS else TTL_MS)
            }
            pending.complete(built)
        }
        return built
    }

    private fun valid(key: Key): Entry? {
        val entry = entries[key] ?: return null
        if (clock.epochMillis() - entry.storedAt > entry.ttl) {
            entries.remove(key)
            return null
        }
        // Re-inserting keeps the map in least-recently-used order for the eviction below.
        entries.remove(key)
        entries[key] = entry
        return entry
    }

    private fun store(key: Key, preview: JsonDict?, ttl: Long) {
        entries.remove(key)
        entries[key] = Entry(preview, clock.epochMillis(), ttl)
        while (entries.size > MAX_ENTRIES) {
            entries.remove(entries.keys.first())
        }
    }
}
