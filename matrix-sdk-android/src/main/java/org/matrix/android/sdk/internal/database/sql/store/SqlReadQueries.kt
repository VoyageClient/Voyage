/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.api.session.room.read.ReadService

/** SQL replacements for the Realm-based ReadQueries (isEventRead / isReadMarkerMoreRecent) + latest synced event. */

internal fun SessionStores.latestSyncedEventId(roomId: String): String? =
        chunk.lastForward(roomId)?.id?.let { chunkId ->
            timelineEvent.getByChunk(chunkId).maxByOrNull { it.displayIndex }?.eventId
        }

internal fun SessionStores.isEventRead(userId: String, roomId: String, eventId: String): Boolean {
    val rrEventId = readReceipt.getReceipt(roomId, userId, ReadService.THREAD_ID_MAIN)?.eventId ?: return false
    if (rrEventId == eventId) return true
    val rrTimelineEvent = timelineEvent.getByRoomAndEventId(roomId, rrEventId)
    val targetTimelineEvent = timelineEvent.getByRoomAndEventId(roomId, eventId)
    return rrTimelineEvent != null && targetTimelineEvent != null && rrTimelineEvent.displayIndex >= targetTimelineEvent.displayIndex
}

internal fun SessionStores.isReadMarkerMoreRecent(roomId: String, eventId: String): Boolean {
    val currentMarker = readMarker.get(roomId) ?: return false
    val markerTimelineEvent = timelineEvent.getByRoomAndEventId(roomId, currentMarker)
    val targetTimelineEvent = timelineEvent.getByRoomAndEventId(roomId, eventId)
    return markerTimelineEvent != null && targetTimelineEvent != null && markerTimelineEvent.displayIndex >= targetTimelineEvent.displayIndex
}
