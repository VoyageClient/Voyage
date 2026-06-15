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

import com.zhuinden.monarchy.Monarchy
import io.realm.Realm
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilitiesService
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.query.isEventRead
import org.matrix.android.sdk.internal.database.query.isReadMarkerMoreRecent
import org.matrix.android.sdk.internal.database.query.latestEvent
import org.matrix.android.sdk.internal.database.query.where
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.sync.handler.room.ReadReceiptHandler
import org.matrix.android.sdk.internal.session.sync.handler.room.RoomFullyReadHandler
import org.matrix.android.sdk.internal.task.Task
import org.matrix.android.sdk.internal.util.awaitTransaction
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
        @SessionDatabase private val monarchy: Monarchy,
        private val roomFullyReadHandler: RoomFullyReadHandler,
        private val readReceiptHandler: ReadReceiptHandler,
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

        if (fullyReadEventId != null && !isReadMarkerMoreRecent(monarchy.realmConfiguration, params.roomId, fullyReadEventId)) {
            if (LocalEcho.isLocalEchoId(fullyReadEventId)) {
                Timber.w("Can't set read marker for local event $fullyReadEventId")
            } else {
                markers[READ_MARKER] = fullyReadEventId
            }
        }

        val shouldCheckIfReadInEventsThread = readReceiptThreadId != null &&
                homeServerCapabilitiesService.getHomeServerCapabilities().canUseThreadReadReceiptsAndNotifications

        if (readReceiptEventId != null &&
                !isEventRead(monarchy.realmConfiguration, userId, params.roomId, readReceiptEventId, shouldCheckIfReadInEventsThread)) {
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

    private fun latestSyncedEventId(roomId: String): String? =
            Realm.getInstance(monarchy.realmConfiguration).use { realm ->
                TimelineEventEntity.latestEvent(realm, roomId = roomId, includesSending = false)?.eventId
            }

    private suspend fun updateDatabase(
            roomId: String,
            readReceiptThreadId: String?,
            markers: Map<String, String>,
            shouldUpdateRoomSummary: Boolean,
            readReceiptType: String,
    ) {
        monarchy.awaitTransaction { realm ->
            val readMarkerId = markers[READ_MARKER]
            val readReceiptId = markers[readReceiptType]
            if (readMarkerId != null) {
                roomFullyReadHandler.handle(realm, roomId, FullyReadContent(readMarkerId))
            }
            if (readReceiptId != null) {
                val readReceiptContent = ReadReceiptHandler.createContent(userId, readReceiptId, readReceiptThreadId, clock.epochMillis())
                readReceiptHandler.handle(realm, roomId, readReceiptContent, false, null)
            }
            if (shouldUpdateRoomSummary) {
                val roomSummary = RoomSummaryEntity.where(realm, roomId).findFirst()
                        ?: return@awaitTransaction
                roomSummary.notificationCount = 0
                roomSummary.highlightCount = 0
                roomSummary.hasUnreadMessages = false
            }
        }
    }
}
