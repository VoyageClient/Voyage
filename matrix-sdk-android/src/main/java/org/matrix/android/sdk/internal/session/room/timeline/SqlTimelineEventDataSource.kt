/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.session.events.model.getRelationContent
import org.matrix.android.sdk.api.session.events.model.isImageMessage
import org.matrix.android.sdk.api.session.events.model.isSticker
import org.matrix.android.sdk.api.session.events.model.isVideoMessage
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.database.mapper.TimelineEventMapper
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.asLiveList
import org.matrix.android.sdk.internal.di.SessionDatabase
import javax.inject.Inject

/** SQLDelight counterpart of [TimelineEventDataSource]. */
internal class SqlTimelineEventDataSource @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val timelineEventMapper: TimelineEventMapper,
) {
    fun getTimelineEvent(roomId: String, eventId: String): TimelineEvent? =
            stores.timelineEvent.getByRoomAndEventId(roomId, eventId)?.let { timelineEventMapper.map(it) }

    fun getTimelineEventLive(roomId: String, eventId: String): LiveData<Optional<TimelineEvent>> =
            database.timelineEventQueries.selectByRoomAndEventId(roomId, eventId)
                    .asLiveList(dispatcher)
                    .map { getTimelineEvent(roomId, eventId).toOptional() }

    fun getAttachmentMessages(roomId: String): List<TimelineEvent> =
            stores.timelineEvent.getByRoom(roomId)
                    .sortedBy { it.root?.originServerTs ?: 0 }
                    .distinctBy { it.eventId }
                    .mapNotNull { timelineEventMapper.map(it).takeIf { te -> te.root.isImageMessage() || te.root.isVideoMessage() || te.root.isSticker() } }

    fun getTimelineEventsRelatedTo(roomId: String, eventType: String, eventId: String): List<TimelineEvent> =
            stores.timelineEvent.getByRoom(roomId)
                    .sortedBy { it.root?.originServerTs ?: 0 }
                    .distinctBy { it.eventId }
                    .mapNotNull {
                        timelineEventMapper.map(it).takeIf { te ->
                            val isEventRelatedTo = te.root.getRelationContent()?.takeIf { rc -> rc.type == eventType && rc.eventId == eventId } != null
                            val isContentRelatedTo = te.root.getClearContent()?.toModel<MessageContent>()
                                    ?.relatesTo?.takeIf { rt -> rt.type == eventType && rt.eventId == eventId } != null
                            isEventRelatedTo || isContentRelatedTo
                        }
                    }
}
