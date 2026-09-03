/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.dehydration

import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.crypto.model.OlmDecryptionResult
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.content.OlmEventContent
import org.matrix.android.sdk.api.session.events.model.content.OlmPayloadContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.util.JSON_DICT_PARAMETERIZED_TYPE
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.util.convertFromUTF8
import org.matrix.olm.OlmAccount
import org.matrix.olm.OlmMessage
import org.matrix.olm.OlmSession
import timber.log.Timber
import javax.inject.Inject

private val loggerTag = LoggerTag("DehydratedDeviceRehydrator", LoggerTag.CRYPTO)

private const val EVENTS_PAGE_LIMIT = 100

/** Nothing should need this many pages; it only stops a broken server from looping us forever. */
private const val MAX_BATCHES = 200

/**
 * Decrypts the to-device events a dehydrated device received while we were away.
 *
 * The dehydrated device never replies to anyone, so every message addressed to it is still a pre-key
 * message and can be decrypted from the account alone: no Olm session ever had to be stored.
 */
internal class DehydratedDeviceRehydrator @Inject constructor(
        @UserId private val userId: String,
        private val dehydratedDeviceApi: DehydratedDeviceApi,
) {

    /**
     * @param onEvent called for each successfully decrypted event, in the order the server gave them
     * @return how many events were decrypted
     */
    suspend fun rehydrate(
            deviceId: String,
            account: OlmAccount,
            onEvent: suspend (Event) -> Unit
    ): Int {
        val identityKeys = account.identityKeys()
        val ourCurve25519Key = identityKeys[OlmAccount.JSON_KEY_IDENTITY_KEY] ?: return 0
        val ourEd25519Key = identityKeys[OlmAccount.JSON_KEY_FINGER_PRINT_KEY] ?: return 0

        var from: String? = null
        var decrypted = 0
        var seenBatches = 0

        do {
            val response = dehydratedDeviceApi.getEvents(deviceId, from, EVENTS_PAGE_LIMIT)
            val events = response.events.orEmpty()
            Timber.tag(loggerTag.value).d("Rehydrating ${events.size} event(s) for $deviceId")

            events.forEach { event ->
                val clear = decryptEvent(event, account, ourCurve25519Key, ourEd25519Key)
                if (clear != null) {
                    decrypted++
                    onEvent(clear)
                }
            }

            from = response.nextBatch
            seenBatches++
        } while (from != null && events.isNotEmpty() && seenBatches < MAX_BATCHES)

        return decrypted
    }

    private fun decryptEvent(
            event: Event,
            account: OlmAccount,
            ourCurve25519Key: String,
            ourEd25519Key: String
    ): Event? {
        val olmEventContent = event.content.toModel<OlmEventContent>() ?: return null
        val senderKey = olmEventContent.senderKey ?: return null

        @Suppress("UNCHECKED_CAST")
        val message = olmEventContent.ciphertext?.get(ourCurve25519Key) as? JsonDict ?: return null
        val body = message["body"] as? String ?: return null
        val messageType = when (val type = message["type"]) {
            is Double -> type.toInt()
            is Int -> type
            is Long -> type.toInt()
            else -> return null
        }
        if (messageType != OlmMessage.MESSAGE_TYPE_PRE_KEY) {
            // Nobody ever got a reply from this device, so anything else can't be for it.
            Timber.tag(loggerTag.value).w("Skipping non pre-key message from $senderKey")
            return null
        }

        val payloadString = decryptPreKeyMessage(account, senderKey, body) ?: return null
        val payload = MoshiProvider.providesMoshi()
                .adapter<JsonDict>(JSON_DICT_PARAMETERIZED_TYPE)
                .fromJson(payloadString) ?: return null
        val olmPayloadContent = OlmPayloadContent.fromJsonString(payloadString) ?: return null

        if (!isPayloadForUs(olmPayloadContent, event, ourEd25519Key)) return null

        return event.copy().apply {
            mxDecryptionResult = OlmDecryptionResult(
                    payload = payload,
                    senderKey = senderKey,
                    keysClaimed = olmPayloadContent.keys,
                    forwardingCurve25519KeyChain = emptyList()
            )
        }
    }

    private fun decryptPreKeyMessage(account: OlmAccount, senderKey: String, body: String): String? {
        var session: OlmSession? = null
        return try {
            session = OlmSession()
            session.initInboundSessionFrom(account, senderKey, body)
            convertFromUTF8(session.decryptMessage(OlmMessage().apply {
                mCipherText = body
                mType = OlmMessage.MESSAGE_TYPE_PRE_KEY.toLong()
            }))
        } catch (failure: Throwable) {
            Timber.tag(loggerTag.value).w("Failed to decrypt an event from $senderKey: ${failure.localizedMessage}")
            null
        } finally {
            session?.releaseSession()
        }
    }

    /** The same unknown-key checks the normal Olm receive path makes. */
    private fun isPayloadForUs(payload: OlmPayloadContent, event: Event, ourEd25519Key: String): Boolean {
        return when {
            payload.recipient != userId -> {
                Timber.tag(loggerTag.value).e("Rejecting an event meant for ${payload.recipient}")
                false
            }
            payload.recipientKeys?.get("ed25519") != ourEd25519Key -> {
                Timber.tag(loggerTag.value).e("Rejecting an event meant for another device")
                false
            }
            payload.sender.isNullOrBlank() || payload.sender != event.senderId -> {
                Timber.tag(loggerTag.value).e("Rejecting an event whose sender does not match ${event.senderId}")
                false
            }
            else -> true
        }
    }
}
