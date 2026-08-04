/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.events

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.events.EventService
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.session.call.CallEventProcessor
import org.matrix.android.sdk.internal.session.room.timeline.GetContextOfEventTask
import org.matrix.android.sdk.internal.session.room.timeline.GetEventTask
import org.matrix.android.sdk.internal.session.room.timeline.RoomSummaryEventDecryptor
import org.matrix.android.sdk.internal.session.room.timeline.TimelineDecryptionSignal
import javax.inject.Inject

internal class DefaultEventService @Inject constructor(
        private val getEventTask: GetEventTask,
        private val getContextOfEventTask: GetContextOfEventTask,
        private val callEventProcessor: CallEventProcessor,
        private val stores: SessionStores,
        private val roomSummaryEventDecryptor: RoomSummaryEventDecryptor,
        private val decryptionSignal: TimelineDecryptionSignal,
) : EventService {

    override suspend fun getEvent(roomId: String, eventId: String): Event {
        val event = getEventTask.execute(GetEventTask.Params(roomId, eventId))
        // Fast lane to the call event processors: try to make the incoming call ring faster
        if (callEventProcessor.shouldProcessFastLane(event.getClearType())) {
            callEventProcessor.processFastLane(event)
        }
        return event
    }

    override fun getEventFromCache(roomId: String, eventId: String): Event? {
        return stores.event.getByEventIdInRoom(roomId, eventId)?.asDomain()
    }

    override suspend fun ensureEventCached(roomId: String, eventId: String, requireTimelineEvent: Boolean): Event? {
        val cached = getEventFromCache(roomId, eventId)
        if (cached != null && (!requireTimelineEvent || stores.timelineEvent.getByRoomAndEventId(roomId, eventId) != null)) {
            return cached
        }
        // Use the same context-fetch task the timeline uses for permalink navigation. It persists
        // the event AND surrounding context through TokenChunkEventPersistor (both EventEntity and
        // TimelineEventEntity rows), which UpdatedReplyDecorator needs to resolve reply targets.
        tryOrNull { getContextOfEventTask.execute(GetContextOfEventTask.Params(roomId, eventId)) } ?: return cached
        return getEventFromCache(roomId, eventId)
    }

    override fun requestDecryption(event: Event) {
        roomSummaryEventDecryptor.requestDecryption(event)
    }

    override fun decryptionUpdates(roomId: String): Flow<Unit> {
        return decryptionSignal.rooms.filter { it == roomId }.map { }
    }
}
