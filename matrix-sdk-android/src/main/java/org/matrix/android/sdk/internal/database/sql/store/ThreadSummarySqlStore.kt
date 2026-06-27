/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.internal.database.model.threads.ThreadSummaryEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.Thread_summary as ThreadSummaryRow

/** SQL access for `thread_summary` + `thread_list_page`, resolving root/latest thread events by db id. */
internal class ThreadSummarySqlStore(
        private val database: SessionSqlDatabase,
        private val eventStore: EventSqlStore,
) {

    private val queries get() = database.threadSummaryQueries

    fun getByRoom(roomId: String): List<ThreadSummaryEntity> = queries.selectByRoom(roomId).executeAsList().map { it.toEntity() }

    fun getByRoomSortedByLatest(roomId: String): List<ThreadSummaryEntity> =
            queries.selectByRoomSortedByLatest(roomId).executeAsList().map { it.toEntity() }

    fun getByRootEventId(roomId: String, rootEventId: String): ThreadSummaryEntity? =
            queries.selectByRootEventId(roomId, rootEventId).executeAsOneOrNull()?.toEntity()

    /** Replace any existing summary for this root, then insert the new one. */
    fun upsert(roomId: String, entity: ThreadSummaryEntity, rootEventDbId: Long?, latestEventDbId: Long?) {
        entity.rootThreadEventId?.let { queries.deleteByRoot(roomId, it) }
        insert(roomId, entity, rootEventDbId, latestEventDbId)
    }

    fun insert(roomId: String, entity: ThreadSummaryEntity, rootEventDbId: Long?, latestEventDbId: Long?) {
        queries.insert(
                room_id = roomId,
                root_thread_event_id = entity.rootThreadEventId,
                root_thread_event_db_id = rootEventDbId,
                latest_thread_event_db_id = latestEventDbId,
                root_thread_sender_name = entity.rootThreadSenderName,
                latest_thread_sender_name = entity.latestThreadSenderName,
                root_thread_sender_avatar = entity.rootThreadSenderAvatar,
                latest_thread_sender_avatar = entity.latestThreadSenderAvatar,
                root_thread_is_unique_display_name = if (entity.rootThreadIsUniqueDisplayName) 1L else 0L,
                is_user_participating = if (entity.isUserParticipating) 1L else 0L,
                latest_thread_is_unique_display_name = if (entity.latestThreadIsUniqueDisplayName) 1L else 0L,
                number_of_threads = entity.numberOfThreads.toLong(),
        )
    }

    fun deleteByRoom(roomId: String) = queries.deleteByRoom(roomId)

    fun upsertPage(roomId: String) = queries.upsertPage(roomId)

    fun hasPage(roomId: String): Boolean = queries.selectPage(roomId).executeAsOneOrNull() != null

    private fun ThreadSummaryRow.toEntity(): ThreadSummaryEntity = ThreadSummaryEntity(
            rootThreadEventId = root_thread_event_id,
            rootThreadEventEntity = root_thread_event_db_id?.let { eventStore.getById(it) },
            latestThreadEventEntity = latest_thread_event_db_id?.let { eventStore.getById(it) },
            rootThreadSenderName = root_thread_sender_name,
            latestThreadSenderName = latest_thread_sender_name,
            rootThreadSenderAvatar = root_thread_sender_avatar,
            latestThreadSenderAvatar = latest_thread_sender_avatar,
            rootThreadIsUniqueDisplayName = root_thread_is_unique_display_name != 0L,
            isUserParticipating = is_user_participating != 0L,
            latestThreadIsUniqueDisplayName = latest_thread_is_unique_display_name != 0L,
            numberOfThreads = number_of_threads.toInt(),
    )
}
