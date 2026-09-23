/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.summary

import org.matrix.android.sdk.api.MatrixConfiguration
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.getRelationContent
import org.matrix.android.sdk.api.session.events.model.isRedacted
import org.matrix.android.sdk.api.session.room.summary.RoomSummaryConstants
import org.matrix.android.sdk.api.settings.LightweightSettingsStorage
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import javax.inject.Inject

/** SQLDelight counterpart of [RoomSummaryEventsHelper]: the room-list preview event. */
internal class SqlRoomSummaryEventsHelper @Inject constructor(
        matrixConfiguration: MatrixConfiguration,
        private val lightweightSettingsStorage: LightweightSettingsStorage,
) {
    private val allowedTypes: Set<String> = RoomSummaryConstants.PREVIEWABLE_TYPES
            .plus(matrixConfiguration.customEventTypesProvider?.customPreviewableEventTypes.orEmpty())
            .toSet()

    /** Returns the newest event that can be rendered as a room-list preview. */
    fun getLatestPreviewableEvent(stores: SessionStores, roomId: String, thorough: Boolean = false): TimelineEventEntity? {
        val ignored = stores.user.getIgnoredUserIds().toSet()
        val sendingEvents = stores.timelineEvent.getSendingByRoom(roomId)
        val sending = sendingEvents.filter { it.isPreviewable(ignored) }
        val chunkId = stores.chunk.lastForward(roomId)?.id
        val liveEvents = chunkId?.let { stores.timelineEvent.getByChunkNewest(it, PREVIEW_SCAN_LIMIT) }.orEmpty()
        val live = liveEvents.firstOrNull { it.isPreviewable(ignored) }
        // The live range holds the room's newest events, so once it has named one there is nothing newer
        // for a room-wide scan to find — and that scan runs for every touched room on every sync.
        val crossChunkEvents = if (thorough || live == null) {
            val limit = if (thorough) PREVIEW_CROSS_CHUNK_THOROUGH_LIMIT else PREVIEW_CROSS_CHUNK_LIMIT
            stores.timelineEvent.getByRoomTypesNewest(roomId, allowedTypes, limit)
        } else {
            emptyList()
        }
        val crossChunk = crossChunkEvents.firstOrNull { it.isPreviewable(ignored) }
        val candidates = listOfNotNull(sending.lastOrNull(), live, crossChunk)
        return candidates.maxWithOrNull(compareBy<TimelineEventEntity>({ it.ts }, { it.eventId }))
                ?: listOf(
                        sendingEvents,
                        liveEvents,
                        crossChunkEvents,
                ).mapNotNull { events -> events.firstOrNull { it.isPreviewableEdit(ignored) } }
                        .maxWithOrNull(compareBy<TimelineEventEntity>({ it.ts }, { it.eventId }))
    }

    fun getLatestUnreadEvent(stores: SessionStores, roomId: String): TimelineEventEntity? {
        val excludedSenders = stores.user.getIgnoredUserIds().ifEmpty { listOf(EMPTY_SENDER) }
        // A reaction is only something to read when the timeline actually shows it.
        val types = if (lightweightSettingsStorage.areReactionsShownInTimeline()) UNREAD_TYPES + EventType.REACTION else UNREAD_TYPES
        return stores.timelineEvent.getLatestUnreadEvent(roomId, types, excludedSenders)
    }

    companion object {
        private const val PREVIEW_SCAN_LIMIT = 200L
        private const val PREVIEW_CROSS_CHUNK_LIMIT = 200L
        private const val PREVIEW_CROSS_CHUNK_THOROUGH_LIMIT = 1_000L

        private const val EMPTY_SENDER = ""
        private val UNREAD_TYPES = listOf(EventType.MESSAGE, EventType.ENCRYPTED, EventType.STICKER) + EventType.POLL_START.values
    }

    private fun TimelineEventEntity.isPreviewable(ignored: Set<String>): Boolean {
        return isPreviewableBase(ignored) && root?.asDomain()?.getRelationContent()?.type != RelationType.REPLACE
    }

    private fun TimelineEventEntity.isPreviewableEdit(ignored: Set<String>): Boolean {
        return isPreviewableBase(ignored) && root?.asDomain()?.getRelationContent()?.type == RelationType.REPLACE
    }

    private fun TimelineEventEntity.isPreviewableBase(ignored: Set<String>): Boolean {
        val root = this.root ?: return false
        // An unknown custom type still previews (as the "not handled" notice) — it IS the room's
        // latest activity. Only known-but-unpreviewable types (reactions, state, calls) are skipped.
        if (root.type !in allowedTypes && EventType.isKnownType(root.type)) return false
        if (root.isUseless) return false
        val domain = root.asDomain()
        // ignored senders' messages must not surface as the room-list preview
        if (domain.senderId != null && domain.senderId in ignored) return false
        // A redacted message stays the preview, as the deleted-message placeholder: the timeline shows it as
        // the room's last entry, so hiding it here made the room list disagree with the room — and disagree
        // with itself, since the stale pointer showed the placeholder until something recomputed. Redacting a
        // reaction is an undo rather than a deletion, so those still fall through to the last real message.
        if (domain.isRedacted() && root.type == EventType.REACTION) return false
        // thread replies belong to their own timeline, not the room's conversation
        if (domain.getRelationContent()?.type == RelationType.THREAD) return false
        return true
    }
}
