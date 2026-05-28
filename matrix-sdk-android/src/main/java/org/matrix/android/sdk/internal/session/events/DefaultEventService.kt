/*
 * Copyright (c) 2021 The Matrix.org Foundation C.I.C.
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

import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.events.EventService
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.internal.database.RealmSessionProvider
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.query.where
import org.matrix.android.sdk.internal.session.call.CallEventProcessor
import org.matrix.android.sdk.internal.session.room.timeline.GetContextOfEventTask
import org.matrix.android.sdk.internal.session.room.timeline.GetEventTask
import javax.inject.Inject

internal class DefaultEventService @Inject constructor(
        private val getEventTask: GetEventTask,
        private val getContextOfEventTask: GetContextOfEventTask,
        private val callEventProcessor: CallEventProcessor,
        private val realmSessionProvider: RealmSessionProvider,
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
        return realmSessionProvider.withRealm { realm ->
            EventEntity.where(
                    realm = realm,
                    roomId = roomId,
                    eventId = eventId
            )
                    .findFirst()
                    ?.asDomain()
        }
    }

    override suspend fun ensureEventCached(roomId: String, eventId: String): Event? {
        getEventFromCache(roomId, eventId)?.let { return it }
        // Use the same context-fetch task the timeline uses for permalink navigation. It
        // persists the event AND surrounding context through TokenChunkEventPersistor, which
        // creates both EventEntity and TimelineEventEntity rows. That second one matters:
        // UpdatedReplyDecorator only finds reply targets via TimelineEventEntity, so a bare
        // EventEntity insert (the way GetEventTask works) would let `getEventFromCache` see
        // the event but the SDK decorator wouldn't, and the inline reply preview would never
        // update with the real body.
        tryOrNull { getContextOfEventTask.execute(GetContextOfEventTask.Params(roomId, eventId)) } ?: return null
        return getEventFromCache(roomId, eventId)
    }
}
