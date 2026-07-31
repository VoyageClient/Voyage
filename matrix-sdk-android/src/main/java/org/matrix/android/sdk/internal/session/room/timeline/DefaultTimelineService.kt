/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import androidx.lifecycle.LiveData
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.TimelineService
import org.matrix.android.sdk.api.session.room.timeline.TimelineSettings
import org.matrix.android.sdk.api.settings.LightweightSettingsStorage
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.internal.database.mapper.TimelineEventMapper
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.session.room.membership.LoadRoomMembersTask
import org.matrix.android.sdk.internal.session.room.relation.threads.FetchThreadTimelineTask
import org.matrix.android.sdk.internal.session.room.send.LocalEchoEventFactory
import org.matrix.android.sdk.internal.session.room.state.StateEventDataSource
import org.matrix.android.sdk.internal.util.time.Clock

internal class DefaultTimelineService @AssistedInject constructor(
        @Assisted private val roomId: String,
        private val timelineInput: TimelineInput,
        private val contextOfEventTask: GetContextOfEventTask,
        private val eventDecryptorProvider: javax.inject.Provider<TimelineEventDecryptor>,
        private val paginationTask: PaginationTask,
        private val fetchTokenAndPaginateTask: FetchTokenAndPaginateTask,
        private val fetchThreadTimelineTask: FetchThreadTimelineTask,
        private val timelineEventMapper: TimelineEventMapper,
        private val loadRoomMembersTask: LoadRoomMembersTask,
        private val lightweightSettingsStorage: LightweightSettingsStorage,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val timelineEventDataSource: SqlTimelineEventDataSource,
        private val clock: Clock,
        private val stateEventDataSource: StateEventDataSource,
        private val localEchoEventFactory: LocalEchoEventFactory,
        private val roomAPI: RoomAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
        @SessionDatabase private val database: org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase,
        @SessionDatabase private val sessionDbDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        @org.matrix.android.sdk.internal.di.SessionDatabaseTimeline private val readDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        private val stores: org.matrix.android.sdk.internal.database.sql.store.SessionStores,
        private val timelineRedactionSignal: TimelineRedactionSignal,
        private val timelineDecryptionSignal: TimelineDecryptionSignal,
) : TimelineService {

    @AssistedFactory
    interface Factory {
        fun create(roomId: String): DefaultTimelineService
    }

    override fun createTimeline(eventId: String?, settings: TimelineSettings): Timeline {
        val snapshotLoader = SqlChunkSnapshotLoader(database, readDispatcher, stores, timelineEventMapper)
        return SqlTimeline(
                roomId = roomId,
                initialEventId = eventId,
                settings = settings,
                coroutineDispatchers = coroutineDispatchers,
                stores = stores,
                snapshotLoader = snapshotLoader,
                paginationTask = paginationTask,
                fetchThreadTimelineTask = fetchThreadTimelineTask,
                contextOfEventTask = contextOfEventTask,
                database = database,
                sessionDispatcher = sessionDbDispatcher,
                readDispatcher = readDispatcher,
                eventDecryptor = eventDecryptorProvider.get(),
                timelineInput = timelineInput,
                clock = clock,
                redactionSignal = timelineRedactionSignal,
                decryptionSignal = timelineDecryptionSignal,
        )
    }

    override fun getTimelineEvent(eventId: String): TimelineEvent? {
        return timelineEventDataSource.getTimelineEvent(roomId, eventId)
    }

    override suspend fun fetchEventIdForTimestamp(timestampMs: Long, forward: Boolean): String? {
        val dir = if (forward) "f" else "b"
        // Server may not implement MSC3030, may 404 when no event exists in that direction,
        // or the network call might fail; all collapse to null.
        return try {
            executeRequest(globalErrorReceiver) {
                roomAPI.getEventForTimestamp(roomId, timestampMs, dir)
            }.eventId
        } catch (failure: Throwable) {
            null
        }
    }

    override fun getTimelineEventLive(eventId: String): LiveData<Optional<TimelineEvent>> {
        return timelineEventDataSource.getTimelineEventLive(roomId, eventId)
    }

    override fun getAttachmentMessages(): List<TimelineEvent> {
        return timelineEventDataSource.getAttachmentMessages(roomId)
    }

    override fun getTimelineEventsRelatedTo(relationType: String, eventId: String): List<TimelineEvent> {
        return timelineEventDataSource.getTimelineEventsRelatedTo(roomId, relationType, eventId)
    }
}
