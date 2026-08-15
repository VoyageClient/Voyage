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

package org.matrix.android.sdk.internal.session.room.create

import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.VersioningState
import org.matrix.android.sdk.api.session.room.model.create.RoomCreateContent
import org.matrix.android.sdk.api.session.room.model.create.RoomPredecessors.toPredecessor
import org.matrix.android.sdk.internal.database.model.EventInsertType
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntity
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.session.EventInsertLiveProcessor
import javax.inject.Inject

internal class RoomCreateEventProcessor @Inject constructor() : EventInsertLiveProcessor {

    override fun process(stores: SessionStores, event: Event) {
        val predecessorRoomId = if (event.type == EventType.STATE_ROOM_CREATE) {
            event.getClearContent().toModel<RoomCreateContent>()?.predecessor?.roomId
        } else {
            event.toPredecessor()?.roomId
        } ?: return

        val predecessorRoomSummary = stores.roomSummary.get(predecessorRoomId)
                ?: RoomSummaryEntity(predecessorRoomId)
        // Flagged as upgraded but deliberately left listed: the old room still holds the history.
        predecessorRoomSummary.versioningState = VersioningState.UPGRADED_ROOM_JOINED
        stores.roomSummary.upsert(predecessorRoomSummary)
    }

    override fun shouldProcess(eventId: String, eventType: String, insertType: EventInsertType): Boolean {
        return eventType == EventType.STATE_ROOM_CREATE || eventType in EventType.STATE_ROOM_PREDECESSOR.values
    }
}
