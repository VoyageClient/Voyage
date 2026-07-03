/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.summary

import org.matrix.android.sdk.api.MatrixConfiguration
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.getRelationContent
import org.matrix.android.sdk.api.session.events.model.isRedacted
import org.matrix.android.sdk.api.session.room.summary.RoomSummaryConstants
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import javax.inject.Inject

/** SQLDelight counterpart of [RoomSummaryEventsHelper]: the room-list preview event. */
internal class SqlRoomSummaryEventsHelper @Inject constructor(
        matrixConfiguration: MatrixConfiguration,
) {
    private val allowedTypes: Set<String> = RoomSummaryConstants.PREVIEWABLE_TYPES
            .plus(matrixConfiguration.customEventTypesProvider?.customPreviewableEventTypes.orEmpty())
            .toSet()

    /**
     * The room-list preview event.
     *
     * [thorough] controls cost vs completeness:
     * - false (the sync / redaction hot path): only the sending queue and the newest slice of the live chunk
     *   are scanned — cheap, runs for every touched room on every sync. When it finds nothing (e.g. the newest
     *   events are a redaction/membership burst) it returns null and the caller keeps the already-persisted
     *   preview, so a dormant room doesn't lose its last message.
     * - true (room open): additionally searches the whole room by timestamp for the newest previewable
     *   message, correcting a stale/missing preview that sits many events back or in an older chunk. The result
     *   is persisted, so it survives restarts until a newer message replaces it (either path).
     */
    fun getLatestPreviewableEvent(stores: SessionStores, roomId: String, thorough: Boolean = false): TimelineEventEntity? {
        val ignored = stores.user.getIgnoredUserIds().toSet()
        val sending = stores.timelineEvent.getSendingByRoom(roomId).filter { it.isPreviewable(ignored) }
        if (sending.isNotEmpty()) {
            return sending.maxByOrNull { it.displayIndex }
        }
        val chunkId = stores.chunk.lastForward(roomId)?.id
        val newest = chunkId?.let {
            stores.timelineEvent.getByChunkNewest(it, PREVIEW_SCAN_LIMIT).firstOrNull { e -> e.isPreviewable(ignored) }
        }
        if (newest != null || !thorough) return newest
        // Only previewable event types are considered here, so a redaction/membership burst ahead of the last
        // real message (even one in an older chunk) doesn't hide it.
        return stores.timelineEvent.getByRoomTypesNewest(roomId, allowedTypes, PREVIEW_CROSS_CHUNK_LIMIT)
                .firstOrNull { it.isPreviewable(ignored) }
    }

    companion object {
        private const val PREVIEW_SCAN_LIMIT = 200L
        private const val PREVIEW_CROSS_CHUNK_LIMIT = 200L
    }

    private fun TimelineEventEntity.isPreviewable(ignored: Set<String>): Boolean {
        val root = this.root ?: return false
        if (root.type !in allowedTypes) return false
        if (root.isUseless) return false
        val domain = root.asDomain()
        // ignored senders' messages must not surface as the room-list preview
        if (domain.senderId != null && domain.senderId in ignored) return false
        if (domain.isRedacted()) return false
        if (domain.getRelationContent()?.type == RelationType.REPLACE) return false
        return true
    }
}
