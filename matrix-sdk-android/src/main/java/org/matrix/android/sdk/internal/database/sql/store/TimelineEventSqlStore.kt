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

    fun getByChunk(chunkId: Long): List<TimelineEventEntity> = queries.selectByChunk(chunkId).executeAsList().toEntities()

    fun getByChunkNewest(chunkId: Long, limit: Long): List<TimelineEventEntity> =
            queries.selectByChunkNewest(chunkId, limit).executeAsList().toEntities()

    fun getByChunkRange(chunkId: Long, from: Long, to: Long): List<TimelineEventEntity> =
            queries.selectByChunkRange(chunkId, from, to).executeAsList().toEntities()

    fun getByChunkAfterIndex(chunkId: Long, afterDisplayIndex: Long): List<TimelineEventEntity> =
            queries.selectByChunkAfterIndex(chunkId, afterDisplayIndex).executeAsList().toEntities()

    fun getByChunkBeforeIndex(chunkId: Long, beforeDisplayIndex: Long, limit: Long): List<TimelineEventEntity> =
            queries.selectByChunkBeforeIndex(chunkId, beforeDisplayIndex, limit).executeAsList().toEntities()

    fun getInChunkByEventId(chunkId: Long, eventId: String): TimelineEventEntity? =
            queries.selectInChunkByEventId(chunkId, eventId).executeAsOneOrNull()?.toEntity()

    fun getByEventId(eventId: String): TimelineEventEntity? = queries.selectByEventId(eventId).executeAsOneOrNull()?.toEntity()

    fun getByRoomAndEventId(roomId: String, eventId: String): TimelineEventEntity? =
            queries.selectByRoomAndEventId(roomId, eventId).executeAsOneOrNull()?.toEntity()

    fun getByRoom(roomId: String): List<TimelineEventEntity> = queries.selectByRoom(roomId).executeAsList().toEntities()

    fun getAttachmentsByRoom(roomId: String): List<TimelineEventEntity> = queries.selectAttachmentsByRoom(roomId).executeAsList().toEntities()

    fun getByRoomTypesAfterTs(roomId: String, types: Collection<String>, ts: Long): List<TimelineEventEntity> =
            queries.selectByRoomTypesAfterTs(roomId, types, ts).executeAsList().toEntities()

    // Excludes sending (chunk_id NULL) local echoes here rather than in SQL — a `chunk_id IS NOT NULL` clause
    // makes SQLDelight infer a distinct non-null-chunk row type that breaks the shared row mapper.
    fun getByRoomTypesNewest(roomId: String, types: Collection<String>, limit: Long): List<TimelineEventEntity> =
            queries.selectByRoomTypesNewest(roomId, types, limit).executeAsList()
                    .filter { it.chunk_id != null }
                    .toEntities()

    fun getSendingByRoom(roomId: String): List<TimelineEventEntity> =
            queries.selectSendingByRoom(roomId).executeAsList().toEntities()

    fun getAllSending(): List<TimelineEventEntity> =
            queries.selectAllSending().executeAsList().toEntities()

    /** timeline_event id of the most recent in-thread reply for the given root (for the root preview). */
    fun latestThreadReplyId(roomId: String, rootThreadEventId: String): Long? =
            queries.selectLatestThreadReplyId(roomId, rootThreadEventId).executeAsOneOrNull()

    fun getRootThreadsForRoom(roomId: String): List<TimelineEventEntity> =
            queries.selectRootThreadsForRoom(roomId).executeAsList().toEntities()

    fun getLocalThreadNotificationsForRoom(roomId: String): List<TimelineEventEntity> =
            queries.selectLocalThreadNotificationsForRoom(roomId).executeAsList().toEntities()

    fun getRootThreadsForRoomLive(roomId: String, dispatcher: CoroutineDispatcher): LiveData<List<TimelineEventEntity>> =
            queries.selectRootThreadsForRoom(roomId).asLiveList(dispatcher).map { rows -> rows.toEntities() }

    fun getLocalThreadNotificationsForRoomLive(roomId: String, dispatcher: CoroutineDispatcher): LiveData<List<TimelineEventEntity>> =
            queries.selectLocalThreadNotificationsForRoom(roomId).asLiveList(dispatcher).map { rows -> rows.toEntities() }

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

    /** Fire timeline_event query listeners for [eventId]'s row without changing it — see touchByEventId. */
    fun touch(eventId: String) = queries.touchByEventId(eventId)

    fun deleteById(id: Long) = queries.deleteById(id)

    fun deleteByChunk(chunkId: Long) = queries.deleteByChunk(chunkId)

    /** Remove all but the first-inserted copy of each event within [chunkIds] (cross-chunk overlap heal). */
    fun deleteDuplicatesInChunks(roomId: String, chunkIds: Collection<Long>) = queries.deleteDuplicatesInChunks(roomId, chunkIds)

    fun deleteSending(roomId: String, eventId: String) = queries.deleteSendingByRoomAndEventId(roomId, eventId)

    fun deleteByRoom(roomId: String) = queries.deleteByRoom(roomId)

    /** Bulk [toEntity]: resolve roots/annotations/receipts for the whole list in a handful of IN queries
     *  instead of ~4 per row — a chunk snapshot re-maps on every sync tick, so the N+1 dominated scroll. */
    private fun List<TimelineEventRow>.toEntities(): List<TimelineEventEntity> {
        if (isEmpty()) return emptyList()
        val roots = eventStore.getByIds(mapNotNull { it.root_event_db_id })
        val eventIds = map { it.event_id }
        val annotations = annotationsStore.getForEventIds(eventIds)
        val receipts = readReceiptStore.getSummaries(eventIds)
        return map { row ->
            TimelineEventEntity(
                    localId = row.local_id,
                    eventId = row.event_id,
                    roomId = row.room_id,
                    displayIndex = row.display_index.toInt(),
                    root = row.root_event_db_id?.let { roots[it] },
                    annotations = annotations[row.event_id],
                    senderName = row.sender_name,
                    isUniqueDisplayName = row.is_unique_display_name != 0L,
                    senderAvatar = row.sender_avatar,
                    senderMembershipEventId = row.sender_membership_event_id,
                    ownedByThreadChunk = row.owned_by_thread_chunk != 0L,
                    readReceipts = receipts[row.event_id],
            )
        }
    }

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
