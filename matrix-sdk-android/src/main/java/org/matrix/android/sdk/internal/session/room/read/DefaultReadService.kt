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

package org.matrix.android.sdk.internal.session.room.read

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.session.room.accountdata.RoomAccountDataTypes
import org.matrix.android.sdk.api.session.room.model.ReadReceipt
import org.matrix.android.sdk.api.session.room.read.ReadService
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.database.mapper.ReadReceiptsSummaryMapper
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.database.sql.store.isEventRead
import org.matrix.android.sdk.internal.session.room.accountdata.UpdateRoomAccountDataTask
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.homeserver.HomeServerCapabilitiesDataSource

internal class DefaultReadService @AssistedInject constructor(
        @Assisted private val roomId: String,
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val setReadMarkersTask: SetReadMarkersTask,
        private val updateRoomAccountDataTask: UpdateRoomAccountDataTask,
        private val readReceiptsSummaryMapper: ReadReceiptsSummaryMapper,
        @UserId private val userId: String,
        private val homeServerCapabilitiesDataSource: HomeServerCapabilitiesDataSource,
        private val matrixCoroutineDispatchers: MatrixCoroutineDispatchers,
) : ReadService {

    @AssistedFactory
    interface Factory {
        fun create(roomId: String): DefaultReadService
    }

    override suspend fun markAsRead(params: ReadService.MarkAsReadParams, mainTimeLineOnly: Boolean, public: Boolean) {
        val readReceiptThreadId = if (homeServerCapabilitiesDataSource.getHomeServerCapabilities()?.canUseThreadReadReceiptsAndNotifications == true) {
            if (mainTimeLineOnly) ReadService.THREAD_ID_MAIN else null
        } else {
            null
        }
        val taskParams = SetReadMarkersTask.Params(
                roomId = roomId,
                forceReadMarker = params.forceReadMarker(),
                forceReadReceipt = params.forceReadReceipt(),
                readReceiptThreadId = readReceiptThreadId,
                publicReadReceipt = public,
        )
        setReadMarkersTask.execute(taskParams)
        if (stores.roomSummary.get(roomId)?.markedUnread == true) {
            setMarkedUnread(false)
        }
    }

    override suspend fun setReadReceipt(eventId: String, threadId: String, public: Boolean) = withContext(matrixCoroutineDispatchers.io) {
        val readReceiptThreadId = if (homeServerCapabilitiesDataSource.getHomeServerCapabilities()?.canUseThreadReadReceiptsAndNotifications == true) {
            threadId
        } else {
            null
        }
        val params = SetReadMarkersTask.Params(
                roomId = roomId,
                fullyReadEventId = null,
                readReceiptEventId = eventId,
                readReceiptThreadId = readReceiptThreadId,
                publicReadReceipt = public,
        )
        setReadMarkersTask.execute(params)
    }

    override suspend fun setReadMarker(fullyReadEventId: String) {
        val params = SetReadMarkersTask.Params(roomId, fullyReadEventId = fullyReadEventId, readReceiptEventId = null)
        setReadMarkersTask.execute(params)
    }

    override suspend fun setMarkedUnread(markedUnread: Boolean) {
        database.awaitDbTransaction(dispatcher) {
            stores.roomSummary.updateMarkedUnread(roomId, markedUnread)
        }
        updateRoomAccountDataTask.execute(
                UpdateRoomAccountDataTask.Params(
                        roomId = roomId,
                        type = RoomAccountDataTypes.MARKED_UNREAD,
                        content = mapOf("unread" to markedUnread)
                )
        )
    }

    override fun isEventRead(eventId: String): Boolean {
        return stores.isEventRead(userId, roomId, eventId)
    }

    override fun getReadMarkerFlow(): Flow<Optional<String>> {
        return database.readMarkerQueries.selectByRoom(roomId).asFlow().mapToList(dispatcher)
                .map { rows -> rows.firstOrNull()?.event_id.toOptional() }
    }

    override fun getMyReadReceiptFlow(threadId: String?): Flow<Optional<String>> {
        return database.readReceiptQueries.selectReceiptForUserInRoom(roomId, userId, threadId).asFlow().mapToList(dispatcher)
                .map { rows -> rows.firstOrNull()?.event_id.toOptional() }
    }

    override fun getUserReadReceipt(userId: String): String? {
        return database.readReceiptQueries.selectMainTimelineReceiptForUser(roomId, userId).executeAsOneOrNull()?.event_id
    }

    override fun getEventReadReceiptsFlow(eventId: String): Flow<List<ReadReceipt>> {
        return database.readReceiptQueries.selectReceiptsForEvent(eventId).asFlow().mapToList(dispatcher)
                .map { stores.readReceipt.getSummary(eventId)?.let { readReceiptsSummaryMapper.map(it) }.orEmpty() }
    }

    private fun ReadService.MarkAsReadParams.forceReadMarker(): Boolean {
        return this == ReadService.MarkAsReadParams.READ_MARKER || this == ReadService.MarkAsReadParams.BOTH
    }

    private fun ReadService.MarkAsReadParams.forceReadReceipt(): Boolean {
        return this == ReadService.MarkAsReadParams.READ_RECEIPT || this == ReadService.MarkAsReadParams.BOTH
    }
}
