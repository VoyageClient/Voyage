/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.getRelationContent
import org.matrix.android.sdk.api.session.events.model.isGalleryMessage
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
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.room.send.LocalEchoRepository
import javax.inject.Inject

/** SQLDelight counterpart of [TimelineEventDataSource]. */
internal class SqlTimelineEventDataSource @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val timelineEventMapper: TimelineEventMapper,
        private val localEchoRepository: Lazy<LocalEchoRepository>,
) {
    fun getTimelineEvent(roomId: String, eventId: String): TimelineEvent? =
            stores.timelineEvent.getByRoomAndEventId(roomId, eventId)?.let { timelineEventMapper.map(it) }
                    ?: resolveLocalEcho(roomId, eventId)

    // A local echo has no DB row yet right after sending (the insert is deferred), and none anymore
    // once the remote copy arrives (the row is deleted and the event lives under its server id).
    // Resolve both windows so lookups by the echo id (long-press sheet, relations) keep working.
    private fun resolveLocalEcho(roomId: String, eventId: String): TimelineEvent? {
        if (!LocalEcho.isLocalEchoId(eventId)) return null
        localEchoRepository.get().getRemoteEchoId(eventId)?.let { remoteId ->
            stores.timelineEvent.getByRoomAndEventId(roomId, remoteId)?.let { return timelineEventMapper.map(it) }
        }
        return localEchoRepository.get().getPendingEcho(eventId)
    }

    fun getTimelineEventFlow(roomId: String, eventId: String): Flow<Optional<TimelineEvent>> =
            database.timelineEventQueries.selectByRoomAndEventId(roomId, eventId)
                    .asFlow()
                    .mapToList(dispatcher)
                    .map { getTimelineEvent(roomId, eventId).toOptional() }

    fun getAttachmentMessages(roomId: String): List<TimelineEvent> =
            stores.timelineEvent.getAttachmentsByRoom(roomId)
                    .distinctBy { it.eventId }
                    .mapNotNull {
                        timelineEventMapper.map(it).takeIf { te ->
                            te.root.isImageMessage() || te.root.isVideoMessage() || te.root.isSticker() || te.root.isGalleryMessage()
                        }
                    }
                    .filterNot { isAcceptedEdition(it) }

    // A caption edit is a full media event carrying m.new_content, so it would otherwise appear as a
    // second copy of the same media in the viewer. Skip replaces that were folded into their target;
    // rejected media edits (never aggregated) fall through and remain visible as distinct media.
    private fun isAcceptedEdition(event: TimelineEvent): Boolean {
        val relation = event.root.getRelationContent()?.takeIf { it.type == RelationType.REPLACE } ?: return false
        val targetId = relation.eventId ?: return false
        val editions = stores.annotations.get(targetId)?.editSummary?.editions ?: return false
        return editions.any { it.eventId == event.eventId }
    }

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
