/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.eventindex

/**
 * Controls the local message search index (the equivalent of Element Desktop's seshat event
 * index). While enabled, messages are indexed as they arrive (decrypted first where needed) and
 * room history is progressively crawled in the background; the search service then answers
 * searches from this index — always for encrypted rooms (the server cannot search those), and
 * for unencrypted rooms too unless [setUnencryptedRoomsEnabled] hands those to the server.
 */
interface EventIndexService {

    /** Whether local indexing is currently enabled. */
    fun isEnabled(): Boolean

    /** Enable or disable indexing and history crawling. Takes effect immediately. */
    fun setEnabled(enabled: Boolean)

    /**
     * Whether unencrypted rooms are indexed (and searched) locally too. When disabled they use
     * the server-side search instead. Encrypted rooms are always local.
     */
    fun setUnencryptedRoomsEnabled(enabled: Boolean)

    /** Number of indexed events and of rooms having indexed events. */
    suspend fun getStats(): EventIndexStats

    /** Delete all indexed events and crawler state. */
    suspend fun clearIndex()
}

data class EventIndexStats(
        val eventCount: Long,
        val roomCount: Long,
)
