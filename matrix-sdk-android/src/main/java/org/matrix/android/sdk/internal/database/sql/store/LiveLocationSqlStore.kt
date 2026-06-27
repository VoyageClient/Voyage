/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.internal.database.model.livelocation.LiveLocationShareAggregatedSummaryEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.Live_location_share_aggregated_summary as LiveLocationRow

/** SQL access for `live_location_share_aggregated_summary`. */
internal class LiveLocationSqlStore(private val database: SessionSqlDatabase) {

    private val queries get() = database.liveLocationShareAggregatedSummaryQueries

    fun get(eventId: String): LiveLocationShareAggregatedSummaryEntity? =
            queries.selectByEventId(eventId).executeAsOneOrNull()?.toEntity()

    fun getByRoom(roomId: String): List<LiveLocationShareAggregatedSummaryEntity> =
            queries.selectByRoom(roomId).executeAsList().map { it.toEntity() }

    fun getRunningByRoom(roomId: String): List<LiveLocationShareAggregatedSummaryEntity> =
            queries.selectRunningByRoom(roomId).executeAsList().map { it.toEntity() }

    fun upsert(entity: LiveLocationShareAggregatedSummaryEntity) = queries.upsert(
            event_id = entity.eventId,
            related_event_ids = entity.relatedEventIds.toList().joinToColumn(),
            room_id = entity.roomId,
            user_id = entity.userId,
            is_active = entity.isActive?.let { if (it) 1L else 0L },
            start_of_live_timestamp_millis = entity.startOfLiveTimestampMillis,
            end_of_live_timestamp_millis = entity.endOfLiveTimestampMillis,
            last_location_content = entity.lastLocationContent,
    )

    fun delete(eventId: String) = queries.deleteByEventId(eventId)

    private fun LiveLocationRow.toEntity(): LiveLocationShareAggregatedSummaryEntity =
            LiveLocationShareAggregatedSummaryEntity(
                    eventId = event_id,
                    relatedEventIds = related_event_ids.splitToRealmList(),
                    roomId = room_id,
                    userId = user_id,
                    isActive = is_active?.let { it != 0L },
                    startOfLiveTimestampMillis = start_of_live_timestamp_millis,
                    endOfLiveTimestampMillis = end_of_live_timestamp_millis,
                    lastLocationContent = last_location_content,
            )
}
