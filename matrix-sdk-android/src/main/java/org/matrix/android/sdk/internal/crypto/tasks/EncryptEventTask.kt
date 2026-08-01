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
package org.matrix.android.sdk.internal.crypto.tasks

import dagger.Lazy
import org.matrix.android.sdk.api.crypto.MXCRYPTO_ALGORITHM_MEGOLM
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.crypto.model.MXEventDecryptionResult
import org.matrix.android.sdk.api.session.crypto.model.MessageVerificationState
import org.matrix.android.sdk.api.session.crypto.model.OlmDecryptionResult
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.session.room.send.LocalEchoRepository
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface EncryptEventTask : Task<EncryptEventTask.Params, Event> {
    data class Params(
            val roomId: String,
            val event: Event,
            /**Do not encrypt these keys, keep them as is in encrypted content (e.g. m.relates_to)*/
            val keepKeys: List<String>? = null
    )
}

internal class DefaultEncryptEventTask @Inject constructor(
        private val localEchoRepository: LocalEchoRepository,
        private val cryptoService: Lazy<CryptoService>
) : EncryptEventTask {
    override suspend fun execute(params: EncryptEventTask.Params): Event {
        // don't want to wait for any query
        // if (!params.crypto.isRoomEncrypted(params.roomId)) return params.event
        val localEvent = params.event
        val eventId = localEvent.eventId
        val type = localEvent.type
        require(eventId != null)
        require(type != null)

        localEchoRepository.updateSendState(eventId, localEvent.roomId, SendState.ENCRYPTING)

        val localMutableContent = localEvent.content?.toMutableMap() ?: mutableMapOf()
        params.keepKeys?.forEach {
            localMutableContent.remove(it)
        }

        // let it throws
        val result = cryptoService.get().encryptEventContent(localMutableContent, type, params.roomId)

        val modifiedContent = HashMap(result.eventContent)
        params.keepKeys?.forEach { toKeep ->
            localEvent.content?.get(toKeep)?.let {
                // put it back in the encrypted thing
                modifiedContent[toKeep] = it
            }
        }
        val safeResult = result.copy(eventContent = modifiedContent)
        // Better handling of local echo, to avoid decrypting transition on remote echo
        // Should I only do it for text messages?
        val decryptionLocalEcho = if (result.eventContent["algorithm"] == MXCRYPTO_ALGORITHM_MEGOLM) {
            MXEventDecryptionResult(
                    clearEvent = Event(
                            type = localEvent.type,
                            content = localEvent.content,
                            roomId = localEvent.roomId
                    ).toContent(),
                    forwardingCurve25519KeyChain = emptyList(),
                    senderCurve25519Key = result.eventContent["sender_key"] as? String,
                    claimedEd25519Key = cryptoService.get().getMyCryptoDevice().fingerprint(),
                    messageVerificationState = MessageVerificationState.VERIFIED
            )
        } else {
            null
        }

        // Async: the send only needs the in-memory encrypted event; awaiting this write would stall
        // the room's send queue behind the DB write dispatcher.
        localEchoRepository.updateEchoAsync(eventId) { localEcho ->
            localEcho.type = EventType.ENCRYPTED
            localEcho.content = ContentMapper.map(modifiedContent)
            // Set the clear local-echo decryption result directly (the entity is an unmanaged SQL DTO,
            // so EventEntity.setDecryptionResult()'s assertIsManaged would throw). updateEcho persists
            // decryption_result_json.
            decryptionLocalEcho?.also { dr ->
                localEcho.decryptionResultJson = MoshiProvider.providesMoshi()
                        .adapter(OlmDecryptionResult::class.java)
                        .toJson(
                                OlmDecryptionResult(
                                        payload = dr.clearEvent,
                                        senderKey = dr.senderCurve25519Key,
                                        keysClaimed = dr.claimedEd25519Key?.let { mapOf("ed25519" to it) },
                                        forwardingCurve25519KeyChain = dr.forwardingCurve25519KeyChain,
                                        verificationState = dr.messageVerificationState,
                                )
                        )
                localEcho.decryptionErrorCode = null
                localEcho.decryptionErrorReason = null
            }
        }
        return localEvent.copy(
                type = safeResult.eventType,
                content = safeResult.eventContent
        )
    }
}
