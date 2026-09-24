/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
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

    internal val queries get() = database.timelineEventQueries

    fun nextLocalId(): Long = queries.nextLocalId().executeAsOne()

    fun getByChunk(chunkId: Long): List<TimelineEventEntity> = queries.selectByChunk(chunkId).executeAsList().toEntities(forTimeline = true)

    fun getByChunkNewest(chunkId: Long, limit: Long): List<TimelineEventEntity> =
            queries.selectByChunkNewest(chunkId, limit).executeAsList().toEntities(forTimeline = true)

    /** Everything the chunk has gained above a known row — what a live append adds to an open timeline. */
    fun getByChunkNewerThan(chunkId: Long, ts: Long, eventId: String): List<TimelineEventEntity> =
            queries.selectByChunkNewerThan(chunkId, ts, ts, eventId).executeAsList().toEntities(forTimeline = true)

    /** The rows immediately below a known one, newest first. Keyset, so it stays an index-only scan. */
    fun getByChunkOlderThan(chunkId: Long, ts: Long, eventId: String, limit: Long): List<TimelineEventEntity> =
            queries.selectByChunkOlderThan(chunkId, ts, ts, eventId, limit).executeAsList().toEntities(forTimeline = true)

    fun getInChunkByEventId(chunkId: Long, eventId: String): TimelineEventEntity? =
            queries.selectInChunkByEventId(chunkId, eventId).executeAsOneOrNull()?.toEntity()

    fun getByEventId(eventId: String): TimelineEventEntity? = queries.selectByEventId(eventId).executeAsOneOrNull()?.toEntity()

    fun getByRoomAndEventId(roomId: String, eventId: String): TimelineEventEntity? =
            queries.selectByRoomAndEventId(roomId, eventId).executeAsOneOrNull()?.toEntity()

    /** [getByRoomAndEventId] for the open timeline's single-event refresh, withholding bulky content so one
     *  event cannot map two different ways and churn the snapshot mapper's memo. */
    fun getByRoomAndEventIdForTimeline(roomId: String, eventId: String): TimelineEventEntity? =
            queries.selectByRoomAndEventId(roomId, eventId).executeAsList().toEntities(forTimeline = true).firstOrNull()

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

    fun getLatestUnreadEvent(roomId: String, types: Collection<String>, excludedSenders: Collection<String>): TimelineEventEntity? =
            queries.selectLatestUnreadEvent(roomId, types, excludedSenders).executeAsOneOrNull()?.toEntity()

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

    fun countByChunk(chunkId: Long): Long = queries.countByChunk(chunkId).executeAsOne()

    /** Rows at or newer than a (ts, event_id) cursor. */
    fun countByChunkFrom(chunkId: Long, ts: Long, eventId: String): Long =
            queries.countByChunkFrom(chunkId, ts, ts, eventId).executeAsOne()

    fun minTsForChunk(chunkId: Long): Long? =
            queries.selectMinTsForChunk(chunkId).executeAsOneOrNull()?.ts

    fun maxTsForChunk(chunkId: Long): Long? =
            queries.selectMaxTsForChunk(chunkId).executeAsOneOrNull()?.ts

    /** Timestamp of the chunk's newest-positioned row, ignoring [excludedRowId]. */
    fun tsAtNewestRow(chunkId: Long, excludedRowId: Long): Long? =
            queries.selectTsAtNewestRowExcluding(chunkId, excludedRowId).executeAsOneOrNull()?.ts

    /** Timestamp of the chunk's oldest-positioned row, ignoring [excludedRowId]. */
    fun tsAtOldestRow(chunkId: Long, excludedRowId: Long): Long? =
            queries.selectTsAtOldestRowExcluding(chunkId, excludedRowId).executeAsOneOrNull()?.ts

    fun insert(entity: TimelineEventEntity, chunkId: Long?, rootEventDbId: Long?): Long {
        queries.insert(
                local_id = entity.localId,
                event_id = entity.eventId,
                room_id = entity.roomId,
                chunk_id = chunkId,
                ts = entity.ts,
                root_event_db_id = rootEventDbId,
                sender_name = entity.senderName,
                is_unique_display_name = if (entity.isUniqueDisplayName) 1L else 0L,
                sender_avatar = entity.senderAvatar,
                sender_membership_event_id = entity.senderMembershipEventId,
                owned_by_thread_chunk = if (entity.ownedByThreadChunk) 1L else 0L,
        )
        return queries.lastInsertRowId().executeAsOne()
    }

    /** Moves rows at or newer than [fromTs] into [targetChunkId]; returns how many moved. */
    fun moveRowsFromTs(sourceChunkId: Long, targetChunkId: Long, fromTs: Long): Int {
        val moving = queries.selectByChunk(sourceChunkId).executeAsList().count { it.ts >= fromTs }
        if (moving > 0) queries.moveRowsFromTs(targetChunkId, sourceChunkId, fromTs)
        return moving
    }

    fun setChunk(id: Long, chunkId: Long?) = queries.updateChunkId(chunkId, id)

    fun clearSenderInfoForMembershipEvent(membershipEventId: String) = queries.clearSenderInfoByMembershipEvent(membershipEventId)

    /** Fire timeline_event query listeners for [eventId]'s row without changing it — see touchByEventId. */
    fun touch(eventId: String) = queries.touchByEventId(eventId)

    fun deleteById(id: Long) = queries.deleteById(id)

    fun deleteByChunk(chunkId: Long) = queries.deleteByChunk(chunkId)

    fun deleteInChunkExcept(chunkId: Long, keepEventIds: Collection<String>) = queries.deleteInChunkExcept(chunkId, keepEventIds)

    /** Remove all but the first-inserted copy of each event within [chunkIds] (cross-chunk overlap heal). */
    fun deleteDuplicatesInChunks(roomId: String, chunkIds: Collection<Long>) = queries.deleteDuplicatesInChunks(roomId, chunkIds)

    fun deleteSending(roomId: String, eventId: String) = queries.deleteSendingByRoomAndEventId(roomId, eventId)

    fun deleteByRoom(roomId: String) = queries.deleteByRoom(roomId)

    /** Bulk [toEntity]: resolve roots/annotations/receipts for the whole list in a handful of IN queries
     *  instead of ~4 per row — a chunk snapshot re-maps on every sync tick, so the N+1 dominated scroll. */
    internal fun List<TimelineEventRow>.toEntities(forTimeline: Boolean = false): List<TimelineEventEntity> {
        if (isEmpty()) return emptyList()
        val rootIds = mapNotNull { it.root_event_db_id }
        val roots = if (forTimeline) eventStore.getByIdsForTimeline(rootIds) else eventStore.getByIds(rootIds)
        val eventIds = map { it.event_id }
        val annotations = annotationsStore.getForEventIds(eventIds)
        val receipts = readReceiptStore.getSummaries(eventIds)
        return map { row ->
            TimelineEventEntity(
                    localId = row.local_id,
                    eventId = row.event_id,
                    roomId = row.room_id,
                    ts = row.ts,
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
            ts = ts,
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
