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

package org.matrix.android.sdk.internal.session.room.send

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getRelationContent
import org.matrix.android.sdk.internal.database.mapper.TimelineEventMapper
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.mapper.toEntity
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.EventInsertType
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.room.membership.SqlRoomMemberHelper
import org.matrix.android.sdk.internal.session.room.summary.SqlRoomSummaryUpdater
import org.matrix.android.sdk.internal.session.room.timeline.TimelineInput
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

// Session-scoped: the pending-echo / local→remote id maps must be shared between the send
// pipeline (writer) and timeline lookups (reader); unscoped, each injection point got its
// own empty copy.
@SessionScope
internal class LocalEchoRepository @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val taskExecutor: TaskExecutor,
        private val roomSummaryUpdater: SqlRoomSummaryUpdater,
        private val timelineInput: TimelineInput,
        private val timelineEventMapper: TimelineEventMapper,
        private val clock: Clock,
) {

    private val sentEchoesByRemoteId = java.util.Collections.synchronizedMap(HashMap<String, String>())

    // The DB insert of a local echo is deferred (see createLocalEcho), and once sent the echo row is
    // replaced by the remote event under a different id. These maps let lookups by the local echo id
    // (e.g. the long-press action sheet, or a redact/reaction targeting a still-sending message)
    // resolve instantly during both windows.
    private val pendingEchoes = java.util.Collections.synchronizedMap(HashMap<String, TimelineEvent>())
    private val remoteIdsByLocalEcho = java.util.Collections.synchronizedMap(HashMap<String, String>())

    fun getPendingEcho(eventId: String): TimelineEvent? = pendingEchoes[eventId]

    fun getRemoteEchoId(localEchoId: String): String? = remoteIdsByLocalEcho[localEchoId]

    fun createLocalEcho(event: Event) {
        val roomId = event.roomId ?: throw IllegalStateException("You should have set a roomId for your event")
        val senderId = event.senderId ?: throw IllegalStateException("You should have set a senderId for your event")
        event.eventId ?: throw IllegalStateException("You should have set an eventId for your event")
        event.type ?: throw IllegalStateException("You should have set a type for your event")

        taskExecutor.executorScope.launch {
            // Build and announce the echo BEFORE queueing the DB write: the session DB dispatcher can
            // run hundreds of ms behind (sync handling, timeline mapping), and the message must show in
            // the timeline the instant it is sent. WAL lets the member reads run on this pool thread.
            val eventEntity = event.toEntity(roomId, SendState.UNSENT, clock.epochMillis())
            val roomMemberHelper = SqlRoomMemberHelper(stores, roomId)
            val myUser = roomMemberHelper.getLastRoomMember(senderId)
            val localId = UUID.randomUUID().mostSignificantBits
            val timelineEventEntity = TimelineEventEntity(localId).also {
                it.root = eventEntity
                it.eventId = event.eventId
                it.roomId = roomId
                it.senderName = myUser?.let { u -> u.displayName ?: "" }
                it.senderAvatar = myUser?.let { u -> u.avatarUrl ?: "" }
                it.isUniqueDisplayName = roomMemberHelper.isUniqueDisplayName(myUser?.displayName)
            }
            val timelineEvent = timelineEventMapper.map(timelineEventEntity)
            pendingEchoes[event.eventId] = timelineEvent
            timelineInput.onLocalEchoCreated(roomId = roomId, timelineEvent = timelineEvent)
            database.awaitDbTransaction(dispatcher) {
                // This write is queued behind sync handling, so a fast send + sync round-trip can
                // deliver the remote copy BEFORE the echo row exists — its reconciliation
                // (deleteSending by transaction id) then hits nothing, and inserting the echo now
                // would leave a stuck duplicate in the pending section forever. Skip it instead.
                val remoteId = remoteIdsByLocalEcho[event.eventId]
                if (remoteId != null && stores.timelineEvent.getByRoomAndEventId(roomId, remoteId) != null) {
                    Timber.i("## Send: skip local echo insert of ${event.eventId}, remote copy $remoteId already synced")
                } else {
                    val dbId = stores.event.insert(eventEntity)
                    stores.eventInsert.insert(event.eventId, event.type, canBeProcessed = true, insertType = EventInsertType.LOCAL_ECHO)
                    stores.timelineEvent.insert(timelineEventEntity, chunkId = null, rootEventDbId = dbId)
                }
                roomSummaryUpdater.updateSendingInformation(stores, roomId)
            }
            pendingEchoes.remove(event.eventId)
        }
    }

    fun updateSendState(eventId: String, roomId: String?, sendState: SendState, sendStateDetails: String? = null) {
        Timber.v("## SendEvent: [${clock.epochMillis()}] Update local state of $eventId to ${sendState.name}")
        timelineInput.onLocalEchoUpdated(roomId = roomId ?: "", eventId = eventId, sendState = sendState)
        updateEchoAsync(eventId) { entity ->
            if (!(sendState == SendState.SENT && entity.sendState == SendState.SYNCED)) {
                entity.sendState = sendState
            }
            entity.sendStateDetails = sendStateDetails
            roomSummaryUpdater.updateSendingInformation(stores, entity.roomId)
        }
    }

    suspend fun onEventSent(roomId: String, localEchoId: String, remoteEventId: String) {
        sentEchoesByRemoteId[remoteEventId] = localEchoId
        remoteIdsByLocalEcho[localEchoId] = remoteEventId
        // Fire-and-forget: this reconciliation must not hold the room's send queue behind the DB
        // write dispatcher. Ordering with createLocalEcho's deferred insert is preserved (same queue).
        taskExecutor.executorScope.launch {
            database.awaitDbTransaction(dispatcher) {
                val remoteExists = stores.timelineEvent.getByRoomAndEventId(roomId, remoteEventId) != null
                if (remoteExists) {
                    deleteSentEcho(roomId, remoteEventId)
                }
            }
        }
    }

    /** Drop a stuck local echo whose remote copy has arrived. Caller must already be on the DB dispatcher. */
    fun deleteSentEcho(roomId: String, remoteEventId: String): Boolean {
        val localEchoId = sentEchoesByRemoteId[remoteEventId] ?: return false
        val echo = stores.timelineEvent.getSendingByRoom(roomId).firstOrNull { it.eventId == localEchoId }
        if (echo == null) {
            // The echo row may not be inserted yet (createLocalEcho's write is still queued):
            // keep the mapping so the insert-time check can skip the late insert.
            return false
        }
        sentEchoesByRemoteId.remove(remoteEventId)
        Timber.v("Remove stuck local echo $localEchoId for synced event $remoteEventId")
        stores.timelineEvent.deleteSending(roomId, localEchoId)
        stores.event.deleteByEventIdInRoom(roomId, localEchoId)
        roomSummaryUpdater.updateSendingInformation(stores, roomId)
        return echo.eventId == localEchoId
    }

    suspend fun updateEcho(eventId: String, block: (EventEntity) -> Unit) {
        database.awaitDbTransaction(dispatcher) {
            stores.event.getByEventId(eventId)?.let { entity ->
                block(entity)
                stores.event.updateEcho(entity)
            }
        }
    }

    fun updateEchoAsync(eventId: String, block: (EventEntity) -> Unit) {
        taskExecutor.executorScope.launch {
            database.awaitDbTransaction(dispatcher) {
                stores.event.getByEventId(eventId)?.let { entity ->
                    block(entity)
                    stores.event.updateEcho(entity)
                }
            }
        }
    }

    suspend fun getUpToDateEcho(eventId: String): Event? {
        return database.awaitDbTransaction(dispatcher) {
            stores.event.getByEventId(eventId)?.asDomain(castJsonNumbers = true)
        }
    }

    suspend fun deleteFailedEcho(roomId: String, localEcho: TimelineEvent) {
        deleteFailedEcho(roomId, localEcho.eventId)
    }

    suspend fun deleteFailedEcho(roomId: String, eventId: String?) {
        eventId ?: return
        pendingEchoes.remove(eventId)
        database.awaitDbTransaction(dispatcher) {
            stores.timelineEvent.deleteSending(roomId, eventId)
            stores.event.deleteByEventIdInRoom(roomId, eventId)
            roomSummaryUpdater.updateSendingInformation(stores, roomId)
        }
    }

    fun deleteFailedEchoAsync(roomId: String, eventId: String?) {
        eventId ?: return
        pendingEchoes.remove(eventId)
        taskExecutor.executorScope.launch {
            database.awaitDbTransaction(dispatcher) {
                stores.timelineEvent.deleteSending(roomId, eventId)
                stores.event.deleteByEventIdInRoom(roomId, eventId)
                roomSummaryUpdater.updateSendingInformation(stores, roomId)
            }
        }
    }

    // Delete every local-echo redaction still in a sending state, across all rooms. Bulk redactions used to
    // create hundreds of these echoes; if they never sent they linger in "sending" forever. Called on session
    // start so a cold launch clears them (the no-echo redaction path doesn't create new ones).
    suspend fun clearAllSendingRedactions() {
        val stuck = stores.timelineEvent.getAllSending()
                .filter { it.root?.type == EventType.REDACTION }
                .mapNotNull { te -> te.root?.roomId?.let { it to te.eventId } }
        if (stuck.isEmpty()) return
        Timber.i("Clearing ${stuck.size} stuck sending redaction echoes on session start")
        stuck.forEach { (roomId, eventId) ->
            pendingEchoes.remove(eventId)
            database.awaitDbTransaction(dispatcher) {
                stores.timelineEvent.deleteSending(roomId, eventId)
                stores.event.deleteByEventIdInRoom(roomId, eventId)
                roomSummaryUpdater.updateSendingInformation(stores, roomId)
            }
        }
    }

    suspend fun clearSendingQueue(roomId: String) {
        database.awaitDbTransaction(dispatcher) {
            stores.timelineEvent.getSendingByRoom(roomId)
                    .filter { it.root?.sendState in SendState.IS_SENDING_STATES }
                    .forEach { stores.event.updateSendState(roomId, it.eventId, SendState.UNSENT, null) }
            roomSummaryUpdater.updateSendingInformation(stores, roomId)
        }
    }

    suspend fun updateSendState(roomId: String, eventIds: List<String>, sendState: SendState) {
        database.awaitDbTransaction(dispatcher) {
            eventIds.forEach { stores.event.updateSendState(roomId, it, sendState, null) }
            roomSummaryUpdater.updateSendingInformation(stores, roomId)
        }
    }

    fun getAllFailedEventsToResend(roomId: String): List<TimelineEvent> = getAllEventsWithStates(roomId, SendState.HAS_FAILED_STATES)

    fun getAllEventsWithStates(roomId: String, states: List<SendState>): List<TimelineEvent> {
        return stores.timelineEvent.getByRoom(roomId)
                .filter { it.root?.sendState in states }
                .sortedByDescending { it.displayIndex }
                .map { timelineEventMapper.map(it) }
                .filter { event ->
                    when (event.root.getClearType()) {
                        EventType.MESSAGE, EventType.REDACTION, EventType.REACTION -> {
                            val content = event.root.getClearContent().toModel<MessageContent>()
                            when (content?.msgType) {
                                MessageType.MSGTYPE_EMOTE, MessageType.MSGTYPE_NOTICE, MessageType.MSGTYPE_LOCATION,
                                MessageType.MSGTYPE_TEXT, MessageType.MSGTYPE_FILE, MessageType.MSGTYPE_VIDEO,
                                MessageType.MSGTYPE_IMAGE, MessageType.MSGTYPE_AUDIO -> true
                                else -> false
                            }
                        }
                        else -> false
                    }
                }
    }

    fun getLatestThreadEvent(rootThreadEventId: String): String =
            stores.event.getByEventId(rootThreadEventId)?.threadSummaryLatestMessage?.eventId ?: rootThreadEventId

    fun getRelatedPollEvent(timelineEvent: TimelineEvent): TimelineEvent? {
        val roomId = timelineEvent.roomId
        val pollEventId = timelineEvent.getRelationContent()?.eventId ?: return null
        return stores.timelineEvent.getByRoomAndEventId(roomId, pollEventId)?.let { timelineEventMapper.map(it) }
    }
}
