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

    fun getLatestPreviewableEvent(stores: SessionStores, roomId: String): TimelineEventEntity? {
        val ignored = stores.user.getIgnoredUserIds().toSet()
        val sending = stores.timelineEvent.getSendingByRoom(roomId).filter { it.isPreviewable(ignored) }
        val candidates = if (sending.isNotEmpty()) {
            sending
        } else {
            val chunkId = stores.chunk.lastForward(roomId)?.id ?: return null
            stores.timelineEvent.getByChunk(chunkId).filter { it.isPreviewable(ignored) }
        }
        return candidates.maxByOrNull { it.displayIndex }
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
