/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.room.read.ReadService

/** SQL replacements for the Realm-based ReadQueries (isEventRead / isReadMarkerMoreRecent) + latest synced event. */

internal fun SessionStores.latestSyncedEventId(roomId: String): String? =
        chunk.lastForward(roomId)?.id?.let { chunkId ->
            timelineEvent.getByChunk(chunkId).maxByOrNull { it.displayIndex }?.eventId
        }

// Mirrors the Realm ReadQueries semantics: local echoes and own events are read by definition, an
// event outside the live chunk is older than the sync window (so read), and otherwise the receipt
// must sit at or past the event within the live chunk — displayIndex is per-chunk, so comparing it
// across chunks (e.g. after a gappy sync started a fresh chunk) would be meaningless.
internal fun SessionStores.isEventRead(userId: String, roomId: String, eventId: String): Boolean {
    if (LocalEcho.isLocalEchoId(eventId)) return true
    val liveChunkId = chunk.lastForward(roomId)?.id ?: return false
    val eventToCheck = timelineEvent.getInChunkByEventId(liveChunkId, eventId)
    return when {
        eventToCheck == null -> true
        eventToCheck.root?.sender == userId -> true
        else -> {
            val rrEventId = readReceipt.getReceipt(roomId, userId, ReadService.THREAD_ID_MAIN)?.eventId ?: return false
            val rrIndex = timelineEvent.getInChunkByEventId(liveChunkId, rrEventId)?.displayIndex ?: Int.MIN_VALUE
            eventToCheck.displayIndex <= rrIndex
        }
    }
}

internal fun SessionStores.isReadMarkerMoreRecent(roomId: String, eventId: String): Boolean {
    val currentMarker = readMarker.get(roomId) ?: return false
    val markerTimelineEvent = timelineEvent.getByRoomAndEventId(roomId, currentMarker)
    val targetTimelineEvent = timelineEvent.getByRoomAndEventId(roomId, eventId)
    return markerTimelineEvent != null && targetTimelineEvent != null && markerTimelineEvent.displayIndex >= targetTimelineEvent.displayIndex
}
