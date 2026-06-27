/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sqldelight.asLiveList
import org.matrix.android.sdk.internal.database.sql.Timeline_event as TimelineEventRow

/**
 * SQL access for `timeline_event`, resolving its root event, annotations and read-receipts refs.
 * A row with chunk_id NULL is a member of a room's `sendingTimelineEvents`.
 */
internal class TimelineEventSqlStore(
        private val database: SessionSqlDatabase,
        private val eventStore: EventSqlStore,
        private val annotationsStore: EventAnnotationsSqlStore,
        private val readReceiptStore: ReadReceiptSqlStore,
) {

    private val queries get() = database.timelineEventQueries

    fun nextLocalId(): Long = queries.nextLocalId().executeAsOne()

    fun getByChunk(chunkId: Long): List<TimelineEventEntity> = queries.selectByChunk(chunkId).executeAsList().map { it.toEntity() }

    fun getByChunkRange(chunkId: Long, from: Long, to: Long): List<TimelineEventEntity> =
            queries.selectByChunkRange(chunkId, from, to).executeAsList().map { it.toEntity() }

    fun getInChunkByEventId(chunkId: Long, eventId: String): TimelineEventEntity? =
            queries.selectInChunkByEventId(chunkId, eventId).executeAsOneOrNull()?.toEntity()

    fun getByEventId(eventId: String): TimelineEventEntity? = queries.selectByEventId(eventId).executeAsOneOrNull()?.toEntity()

    fun getByRoomAndEventId(roomId: String, eventId: String): TimelineEventEntity? =
            queries.selectByRoomAndEventId(roomId, eventId).executeAsOneOrNull()?.toEntity()

    fun getByRoom(roomId: String): List<TimelineEventEntity> = queries.selectByRoom(roomId).executeAsList().map { it.toEntity() }

    fun getByRoomTypesAfterTs(roomId: String, types: Collection<String>, ts: Long): List<TimelineEventEntity> =
            queries.selectByRoomTypesAfterTs(roomId, types, ts).executeAsList().map { it.toEntity() }

    fun getSendingByRoom(roomId: String): List<TimelineEventEntity> =
            queries.selectSendingByRoom(roomId).executeAsList().map { it.toEntity() }

    /** timeline_event id of the most recent in-thread reply for the given root (for the root preview). */
    fun latestThreadReplyId(roomId: String, rootThreadEventId: String): Long? =
            queries.selectLatestThreadReplyId(roomId, rootThreadEventId).executeAsOneOrNull()

    fun getRootThreadsForRoom(roomId: String): List<TimelineEventEntity> =
            queries.selectRootThreadsForRoom(roomId).executeAsList().map { it.toEntity() }

    fun getLocalThreadNotificationsForRoom(roomId: String): List<TimelineEventEntity> =
            queries.selectLocalThreadNotificationsForRoom(roomId).executeAsList().map { it.toEntity() }

    fun getRootThreadsForRoomLive(roomId: String, dispatcher: CoroutineDispatcher): LiveData<List<TimelineEventEntity>> =
            queries.selectRootThreadsForRoom(roomId).asLiveList(dispatcher).map { rows -> rows.map { it.toEntity() } }

    fun getLocalThreadNotificationsForRoomLive(roomId: String, dispatcher: CoroutineDispatcher): LiveData<List<TimelineEventEntity>> =
            queries.selectLocalThreadNotificationsForRoom(roomId).asLiveList(dispatcher).map { rows -> rows.map { it.toEntity() } }

    fun countByChunk(chunkId: Long): Long = queries.countByChunk(chunkId).executeAsOne()

    fun maxDisplayIndex(chunkId: Long): Long? = queries.maxDisplayIndexForChunk(chunkId).executeAsOne().max

    fun minDisplayIndex(chunkId: Long): Long? = queries.minDisplayIndexForChunk(chunkId).executeAsOne().min

    fun insert(entity: TimelineEventEntity, chunkId: Long?, rootEventDbId: Long?): Long {
        queries.insert(
                local_id = entity.localId,
                event_id = entity.eventId,
                room_id = entity.roomId,
                chunk_id = chunkId,
                display_index = entity.displayIndex.toLong(),
                root_event_db_id = rootEventDbId,
                sender_name = entity.senderName,
                is_unique_display_name = if (entity.isUniqueDisplayName) 1L else 0L,
                sender_avatar = entity.senderAvatar,
                sender_membership_event_id = entity.senderMembershipEventId,
                owned_by_thread_chunk = if (entity.ownedByThreadChunk) 1L else 0L,
        )
        return queries.lastInsertRowId().executeAsOne()
    }

    fun setChunk(id: Long, chunkId: Long?) = queries.updateChunkId(chunkId, id)

    fun clearSenderInfoForMembershipEvent(membershipEventId: String) = queries.clearSenderInfoByMembershipEvent(membershipEventId)

    fun deleteById(id: Long) = queries.deleteById(id)

    fun deleteByChunk(chunkId: Long) = queries.deleteByChunk(chunkId)

    fun deleteSending(roomId: String, eventId: String) = queries.deleteSendingByRoomAndEventId(roomId, eventId)

    fun deleteByRoom(roomId: String) = queries.deleteByRoom(roomId)

    private fun TimelineEventRow.toEntity(): TimelineEventEntity = TimelineEventEntity(
            localId = local_id,
            eventId = event_id,
            roomId = room_id,
            displayIndex = display_index.toInt(),
            root = root_event_db_id?.let { eventStore.getById(it) },
            annotations = annotationsStore.get(event_id),
            senderName = sender_name,
            isUniqueDisplayName = is_unique_display_name != 0L,
            senderAvatar = sender_avatar,
            senderMembershipEventId = sender_membership_event_id,
            ownedByThreadChunk = owned_by_thread_chunk != 0L,
            readReceipts = readReceiptStore.getSummary(event_id),
    )
}
