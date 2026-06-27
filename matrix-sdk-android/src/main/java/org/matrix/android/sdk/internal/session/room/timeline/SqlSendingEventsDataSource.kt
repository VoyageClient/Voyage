/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.internal.database.mapper.TimelineEventMapper
import org.matrix.android.sdk.internal.database.sql.store.SessionStores

/**
 * SQLDelight counterpart of [RealmSendingEventsDataSource]. Sending events are the room's
 * timeline_event rows with chunk_id NULL. The Realm version observed the RealmList live; here the
 * owning timeline triggers [refresh] when it rebuilds its snapshot (see the DefaultTimeline rewrite).
 */
internal class SqlSendingEventsDataSource(
        private val roomId: String,
        private val stores: SessionStores,
        private val uiEchoManager: UIEchoManager,
        private val timelineEventMapper: TimelineEventMapper,
        @Suppress("unused") private val onEventsUpdated: (Boolean) -> Unit,
) : SendingEventsDataSource {

    private var mappedSendingTimelineEvents: List<TimelineEvent> = emptyList()

    override fun start() {
        refresh()
    }

    override fun stop() {
        mappedSendingTimelineEvents = emptyList()
    }

    fun refresh() {
        mappedSendingTimelineEvents = stores.timelineEvent.getSendingByRoom(roomId).map { timelineEventMapper.map(it) }
        uiEchoManager.onSentEventsInDatabase(mappedSendingTimelineEvents.map { it.eventId })
    }

    override fun buildSendingEvents(): List<TimelineEvent> {
        val builtSendingEvents = mutableListOf<TimelineEvent>()
        uiEchoManager.getInMemorySendingEvents().addWithUiEcho(builtSendingEvents)
        mappedSendingTimelineEvents
                .filter { timelineEvent -> builtSendingEvents.none { it.eventId == timelineEvent.eventId } }
                .addWithUiEcho(builtSendingEvents)
        return builtSendingEvents
    }

    private fun List<TimelineEvent>.addWithUiEcho(target: MutableList<TimelineEvent>) {
        target.addAll(map { uiEchoManager.updateSentStateWithUiEcho(it) })
    }
}
