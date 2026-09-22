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

package org.matrix.android.sdk.internal.session.room.state

import dagger.Lazy
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.localecho.RoomLocalEcho
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.internal.crypto.EncryptedStateEvents
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.session.room.create.CreateRoomFromLocalRoomTask
import org.matrix.android.sdk.internal.task.Task
import timber.log.Timber
import javax.inject.Inject

internal interface SendStateTask : Task<SendStateTask.Params, String> {
    data class Params(
            val roomId: String,
            val stateKey: String,
            val eventType: String,
            val body: JsonDict,
            /** MSC4362: null follows the room's own setting; true and false override it. */
            val encrypt: Boolean? = null,
    )
}

internal class DefaultSendStateTask @Inject constructor(
        private val roomAPI: RoomAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
        private val createRoomFromLocalRoomTask: CreateRoomFromLocalRoomTask,
        private val cryptoService: Lazy<CryptoService>,
) : SendStateTask {

    override suspend fun execute(params: SendStateTask.Params): String {
        return executeRequest(globalErrorReceiver) {
            if (RoomLocalEcho.isLocalEchoId(params.roomId)) {
                // Room is local, so create a real one and send the event to this new room
                createRoomAndSendEvent(params)
            } else {
                val event = encryptIfNeeded(params)
                val response = if (event.stateKey.isEmpty()) {
                    roomAPI.sendStateEvent(
                            roomId = event.roomId,
                            stateEventType = event.eventType,
                            params = event.body
                    )
                } else {
                    roomAPI.sendStateEvent(
                            roomId = event.roomId,
                            stateEventType = event.eventType,
                            stateKey = event.stateKey,
                            params = event.body
                    )
                }
                response.eventId.also {
                    Timber.d("State event: $it just sent in room ${params.roomId}")
                }
            }
        }
    }

    private suspend fun encryptIfNeeded(params: SendStateTask.Params): SendStateTask.Params {
        if (params.eventType in EncryptedStateEvents.UNENCRYPTABLE_TYPES) return params
        val encrypt = params.encrypt ?: cryptoService.get().isStateEncryptionEnabled(params.roomId)
        if (!encrypt) return params
        awaitEncryptionReady(params.roomId)
        val encrypted = cryptoService.get().encryptEventContent(params.body, params.eventType, params.roomId, params.stateKey)
        return params.copy(
                stateKey = EncryptedStateEvents.packStateKey(params.eventType, params.stateKey),
                eventType = EventType.ENCRYPTED,
                body = encrypted.eventContent,
        )
    }

    // Callers that force encryption send right after creating the room, before m.room.encryption has
    // come back down sync — without the key the room has, there is nothing to encrypt to. Returns at
    // once in a room whose algorithm is already known.
    private suspend fun awaitEncryptionReady(roomId: String) {
        withTimeoutOrNull(ENCRYPTION_READY_TIMEOUT_MS) {
            while (cryptoService.get().getEncryptionAlgorithm(roomId) == null) {
                delay(500)
            }
        } ?: throw IllegalStateException("Timed out waiting for encryption to be enabled in $roomId")
    }

    private suspend fun createRoomAndSendEvent(params: SendStateTask.Params): String {
        val roomId = createRoomFromLocalRoomTask.execute(CreateRoomFromLocalRoomTask.Params(params.roomId))
        Timber.d("State event: convert local room (${params.roomId}) to existing room ($roomId) before sending the event.")
        return execute(params.copy(roomId = roomId))
    }

    companion object {
        private const val ENCRYPTION_READY_TIMEOUT_MS = 30_000L
    }
}
