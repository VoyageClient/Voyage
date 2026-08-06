/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.read.ReadService
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.ReadReceiptEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.session.room.timeline.PaginationDirection

/**
 * Write-path port of `ChunkEntityHelper.addTimelineEvent` & friends. Inserts a [TimelineEventEntity]
 * into a chunk (by chunk id), computing the display index, sender uniqueness, and the sender's dummy
 * read receipt. Annotations/read-receipt summaries are joined by event_id at read time, so they need no
 * FK here. The root event must already be inserted; its db id is passed as [eventDbId].
 */
internal class TimelineSqlWriter(private val stores: SessionStores) {

    fun addTimelineEvent(
            chunkId: Long,
            roomId: String,
            eventDbId: Long,
            event: EventEntity,
            isLastForward: Boolean,
            direction: PaginationDirection,
            ownedByThreadChunk: Boolean = false,
            roomMemberContentsByUser: Map<String, RoomMemberContent?>? = null,
            // Which m.room.member event supplied the cached sender profile, so redacting that event can
            // invalidate every row it fed. Absent for callers that don't track it; those rows simply
            // keep their cached profile.
            roomMemberEventIdsByUser: Map<String, String?>? = null,
    ): Long? {
        val eventId = event.eventId
        if (stores.timelineEvent.getInChunkByEventId(chunkId, eventId) != null) return null

        val displayIndex = nextDisplayIndex(chunkId, direction)
        val localId = stores.timelineEvent.nextLocalId()
        val senderId = event.sender ?: ""
        handleReadReceiptsOfSender(roomId, event, senderId)

        val roomMemberContent = roomMemberContentsByUser?.get(senderId)
        val isUnique = if (roomMemberContent?.displayName != null) {
            computeIsUnique(roomId, isLastForward, roomMemberContent, roomMemberContentsByUser)
        } else {
            true
        }

        val entity = TimelineEventEntity(
                localId = localId,
                eventId = eventId,
                roomId = roomId,
                displayIndex = displayIndex,
                // "" (vs null) means the member state was known at write time and the field was
                // genuinely empty, so readers must not fall back to the live profile for it.
                senderName = roomMemberContent?.let { it.displayName ?: "" },
                isUniqueDisplayName = isUnique,
                senderAvatar = roomMemberContent?.let { it.avatarUrl ?: "" },
                senderMembershipEventId = roomMemberEventIdsByUser?.get(senderId),
                ownedByThreadChunk = ownedByThreadChunk,
        )
        return stores.timelineEvent.insert(entity, chunkId, eventDbId)
    }

    fun nextDisplayIndex(chunkId: Long, direction: PaginationDirection): Int = when (direction) {
        PaginationDirection.FORWARDS -> (stores.timelineEvent.maxDisplayIndex(chunkId)?.toInt() ?: 0) + 1
        PaginationDirection.BACKWARDS -> (stores.timelineEvent.minDisplayIndex(chunkId)?.toInt() ?: 0) - 1
    }

    private fun handleReadReceiptsOfSender(roomId: String, event: EventEntity, senderId: String) {
        stores.readReceipt.upsertSummary(event.eventId, roomId)
        val originServerTs = event.originServerTs ?: return
        val timestampOfEvent = originServerTs.toDouble()
        val threadId = event.rootThreadEventId ?: ReadService.THREAD_ID_MAIN
        val existing = stores.readReceipt.getReceipt(roomId, senderId, threadId)
        if (existing == null || timestampOfEvent > existing.originServerTs) {
            stores.readReceipt.upsertReceipt(
                    ReadReceiptEntity(
                            primaryKey = "${roomId}_${senderId}_$threadId",
                            eventId = event.eventId,
                            roomId = roomId,
                            userId = senderId,
                            threadId = threadId,
                            originServerTs = timestampOfEvent,
                    )
            )
        }
    }

    private fun computeIsUnique(
            roomId: String,
            isLastForward: Boolean,
            senderRoomMemberContent: RoomMemberContent,
            roomMemberContentsByUser: Map<String, RoomMemberContent?>,
    ): Boolean {
        val isHistoricalUnique = roomMemberContentsByUser.values.none {
            it != senderRoomMemberContent && it?.displayName == senderRoomMemberContent.displayName
        }
        return if (isLastForward) {
            val isLiveUnique = stores.roomMember.getByRoom(roomId)
                    .filter { it.displayName == senderRoomMemberContent.displayName }
                    .none { !roomMemberContentsByUser.containsKey(it.userId) }
            isHistoricalUnique && isLiveUnique
        } else {
            isHistoricalUnique
        }
    }
}
