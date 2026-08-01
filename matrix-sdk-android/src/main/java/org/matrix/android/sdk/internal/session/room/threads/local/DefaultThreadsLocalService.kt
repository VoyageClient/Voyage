/*
 * Copyright 2022 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.room.threads.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.session.room.threads.local.ThreadsLocalService
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.threads.ThreadNotificationState
import org.matrix.android.sdk.internal.database.mapper.TimelineEventMapper
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sql.store.getLocalThreadNotificationsForRoomFlow
import org.matrix.android.sdk.internal.database.sql.store.getRootThreadsForRoomFlow
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.UserId

internal class DefaultThreadsLocalService @AssistedInject constructor(
        @Assisted private val roomId: String,
        @UserId private val userId: String,
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val timelineEventMapper: TimelineEventMapper,
) : ThreadsLocalService {

    @AssistedFactory
    interface Factory {
        fun create(roomId: String): DefaultThreadsLocalService
    }

    override fun getMarkedThreadNotificationsFlow(): Flow<List<TimelineEvent>> =
            stores.timelineEvent.getLocalThreadNotificationsForRoomFlow(roomId, dispatcher)
                    .map { entities -> entities.map { timelineEventMapper.map(it) } }

    override fun getMarkedThreadNotifications(): List<TimelineEvent> =
            stores.timelineEvent.getLocalThreadNotificationsForRoom(roomId).map { timelineEventMapper.map(it) }

    override fun getAllThreadsFlow(): Flow<List<TimelineEvent>> =
            stores.timelineEvent.getRootThreadsForRoomFlow(roomId, dispatcher)
                    .map { entities -> entities.map { timelineEventMapper.map(it) }.sortByLatest() }

    override fun getAllThreads(): List<TimelineEvent> =
            stores.timelineEvent.getRootThreadsForRoom(roomId).map { timelineEventMapper.map(it) }.sortByLatest()

    override fun isUserParticipatingInThread(rootThreadEventId: String): Boolean =
            stores.event.isUserParticipatingInThread(roomId, rootThreadEventId, userId)

    override fun mapEventsWithEdition(threads: List<TimelineEvent>): List<TimelineEvent> =
            threads.map { timelineEvent ->
                val editedEventId = stores.annotations.get(timelineEvent.eventId)
                        ?.editSummary?.editions?.lastOrNull()?.eventId
                        ?: return@map timelineEvent
                val editedEvent = stores.event.getByEventIdInRoom(roomId, editedEventId) ?: return@map timelineEvent
                timelineEvent.root.threadDetails = timelineEvent.root.threadDetails?.copy(
                        lastRootThreadEdition = editedEvent.asDomain().getDecryptedTextSummary() ?: "(edited)"
                )
                timelineEvent
            }

    override suspend fun markThreadAsRead(rootThreadEventId: String) {
        database.awaitDbTransaction(dispatcher) {
            stores.event.updateThreadNotificationState(rootThreadEventId, ThreadNotificationState.NO_NEW_MESSAGE)
        }
    }

    // The latest-thread-message ref isn't resolved on the SQL DTO yet, so fall back to the root event ts.
    private fun List<TimelineEvent>.sortByLatest(): List<TimelineEvent> =
            sortedByDescending { it.root.threadDetails?.lastMessageTimestamp ?: it.root.originServerTs }
}
