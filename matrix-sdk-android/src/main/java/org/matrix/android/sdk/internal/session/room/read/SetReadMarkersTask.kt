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

import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilitiesService
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.sql.store.isEventRead
import org.matrix.android.sdk.internal.database.sql.store.isReadMarkerMoreRecent
import org.matrix.android.sdk.internal.database.sql.store.latestSyncedEventId
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.sync.handler.room.SqlReadReceiptHandler
import org.matrix.android.sdk.internal.task.Task
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber
import javax.inject.Inject
import kotlin.collections.set

internal interface SetReadMarkersTask : Task<SetReadMarkersTask.Params, Unit> {

    data class Params(
            val roomId: String,
            val fullyReadEventId: String? = null,
            val readReceiptEventId: String? = null,
            val readReceiptThreadId: String? = null,
            val forceReadReceipt: Boolean = false,
            val forceReadMarker: Boolean = false,
            /**
             * When true, the read receipt is sent as the public `m.read` (other users see it).
             * When false (default), it's sent as `m.read.private` — server still tracks the
             * read state so unread counts and cross-device sync work, but peers don't see it.
             */
            val publicReadReceipt: Boolean = false,
    )
}

private const val READ_MARKER = "m.fully_read"

internal class DefaultSetReadMarkersTask @Inject constructor(
        @SessionDatabase private val database: org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase,
        @SessionDatabase private val sessionDbDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        private val stores: org.matrix.android.sdk.internal.database.sql.store.SessionStores,
        private val roomFullyReadHandler: org.matrix.android.sdk.internal.session.sync.handler.room.SqlRoomFullyReadHandler,
        private val readReceiptHandler: org.matrix.android.sdk.internal.session.sync.handler.room.SqlReadReceiptHandler,
        @UserId private val userId: String,
        private val readReceiptQueue: ReadReceiptQueue,
        private val clock: Clock,
        private val homeServerCapabilitiesService: HomeServerCapabilitiesService,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
) : SetReadMarkersTask {

    override suspend fun execute(params: SetReadMarkersTask.Params) = withContext(coroutineDispatchers.io) {
        val markers = mutableMapOf<String, String>()
        Timber.v("Execute set read marker with params: $params")
        val latestSyncedEventId = latestSyncedEventId(params.roomId)
        val readReceiptThreadId = params.readReceiptThreadId
        val fullyReadEventId = if (params.forceReadMarker) {
            latestSyncedEventId
        } else {
            params.fullyReadEventId
        }
        val readReceiptEventId = if (params.forceReadReceipt) {
            latestSyncedEventId
        } else {
            params.readReceiptEventId
        }
        val readReceiptType = if (params.publicReadReceipt) "m.read" else "m.read.private"

        if (fullyReadEventId != null && !stores.isReadMarkerMoreRecent(params.roomId, fullyReadEventId)) {
            if (LocalEcho.isLocalEchoId(fullyReadEventId)) {
                Timber.w("Can't set read marker for local event $fullyReadEventId")
            } else {
                markers[READ_MARKER] = fullyReadEventId
            }
        }

        if (readReceiptEventId != null &&
                !stores.isEventRead(userId, params.roomId, readReceiptEventId)) {
            if (LocalEcho.isLocalEchoId(readReceiptEventId)) {
                Timber.w("Can't set read receipt for local event $readReceiptEventId")
            } else {
                markers[readReceiptType] = readReceiptEventId
            }
        }

        val shouldUpdateRoomSummary = readReceiptEventId != null && readReceiptEventId == latestSyncedEventId
        if (markers.isNotEmpty() || shouldUpdateRoomSummary) {
            updateDatabase(params.roomId, readReceiptThreadId, markers, shouldUpdateRoomSummary, readReceiptType)
        }
        if (markers.isNotEmpty()) {
            // Hand off to the persistent queue: it retries with backoff and survives restarts, so the
            // server eventually learns the room was read even if the network is currently down.
            readReceiptQueue.enqueue(
                    roomId = params.roomId,
                    fullyReadEventId = markers[READ_MARKER],
                    readReceiptEventId = markers[readReceiptType],
                    readReceiptType = readReceiptType,
                    threadId = params.readReceiptThreadId,
            )
        }
    }

    private fun latestSyncedEventId(roomId: String): String? = stores.latestSyncedEventId(roomId)

    private suspend fun updateDatabase(
            roomId: String,
            readReceiptThreadId: String?,
            markers: Map<String, String>,
            shouldUpdateRoomSummary: Boolean,
            readReceiptType: String,
    ) {
        database.awaitDbTransaction(sessionDbDispatcher) {
            val readMarkerId = markers[READ_MARKER]
            val readReceiptId = markers[readReceiptType]
            if (readMarkerId != null) {
                roomFullyReadHandler.handle(stores, roomId, FullyReadContent(readMarkerId))
            }
            if (readReceiptId != null) {
                val readReceiptContent = SqlReadReceiptHandler.createContent(userId, readReceiptId, readReceiptThreadId, clock.epochMillis())
                readReceiptHandler.handle(stores, roomId, readReceiptContent, false, null)
            }
            if (shouldUpdateRoomSummary) {
                stores.roomSummary.clearUnreadCounters(roomId)
            }
        }
    }
}
