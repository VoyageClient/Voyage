/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.internal.database.model.PollHistoryStatusEntity
import org.matrix.android.sdk.internal.database.model.SyncFilterParamsEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase

/** `poll_history_status` per-room store. */
internal class PollHistorySqlStore(private val database: SessionSqlDatabase) {
    private val queries get() = database.pollHistoryStatusQueries

    fun get(roomId: String): PollHistoryStatusEntity? = queries.selectByRoom(roomId).executeAsOneOrNull()?.let {
        PollHistoryStatusEntity(
                roomId = it.room_id,
                currentTimestampTargetBackwardMs = it.current_timestamp_target_backward_ms,
                oldestTimestampTargetReachedMs = it.oldest_timestamp_target_reached_ms,
                oldestEventIdReached = it.oldest_event_id_reached,
                mostRecentEventIdReached = it.most_recent_event_id_reached,
                isEndOfPollsBackward = it.is_end_of_polls_backward != 0L,
        )
    }

    fun upsert(entity: PollHistoryStatusEntity) = queries.upsert(
            room_id = entity.roomId,
            current_timestamp_target_backward_ms = entity.currentTimestampTargetBackwardMs,
            oldest_timestamp_target_reached_ms = entity.oldestTimestampTargetReachedMs,
            oldest_event_id_reached = entity.oldestEventIdReached,
            most_recent_event_id_reached = entity.mostRecentEventIdReached,
            is_end_of_polls_backward = if (entity.isEndOfPollsBackward) 1L else 0L,
    )

    fun delete(roomId: String) = queries.deleteByRoom(roomId)
}

/** Single-row `sync_filter_params` store. NULL list column = null list (distinct from empty). */
internal class SyncFilterParamsSqlStore(private val database: SessionSqlDatabase) {
    private val queries get() = database.syncFilterParamsQueries

    fun get(): SyncFilterParamsEntity? = queries.selectFirst().executeAsOneOrNull()?.let {
        SyncFilterParamsEntity(
                lazyLoadMembersForStateEvents = it.lazy_load_members_for_state_events?.let { v -> v != 0L },
                lazyLoadMembersForMessageEvents = it.lazy_load_members_for_message_events?.let { v -> v != 0L },
                useThreadNotifications = it.use_thread_notifications?.let { v -> v != 0L },
                listOfSupportedEventTypes = it.list_of_supported_event_types?.toRealmList(),
                listOfSupportedEventTypesHasBeenSet = it.list_of_supported_event_types_has_been_set != 0L,
                listOfSupportedStateEventTypes = it.list_of_supported_state_event_types?.toRealmList(),
                listOfSupportedStateEventTypesHasBeenSet = it.list_of_supported_state_event_types_has_been_set != 0L,
        )
    }

    fun upsert(entity: SyncFilterParamsEntity) = queries.upsert(
            lazy_load_members_for_state_events = entity.lazyLoadMembersForStateEvents?.let { if (it) 1L else 0L },
            lazy_load_members_for_message_events = entity.lazyLoadMembersForMessageEvents?.let { if (it) 1L else 0L },
            use_thread_notifications = entity.useThreadNotifications?.let { if (it) 1L else 0L },
            list_of_supported_event_types = entity.listOfSupportedEventTypes?.toList()?.joinToColumn(),
            list_of_supported_event_types_has_been_set = if (entity.listOfSupportedEventTypesHasBeenSet) 1L else 0L,
            list_of_supported_state_event_types = entity.listOfSupportedStateEventTypes?.toList()?.joinToColumn(),
            list_of_supported_state_event_types_has_been_set = if (entity.listOfSupportedStateEventTypesHasBeenSet) 1L else 0L,
    )

    private fun String.toRealmList(): MutableList<String> =
            ArrayList<String>().apply { if (isNotEmpty()) addAll(split("\n")) }
}
