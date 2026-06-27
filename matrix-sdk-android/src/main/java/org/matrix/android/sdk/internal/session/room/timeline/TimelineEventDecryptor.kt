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
package org.matrix.android.sdk.internal.session.room.timeline

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.crypto.NewSessionListener
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.content.EncryptedEventContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

internal class TimelineEventDecryptor @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val cryptoService: CryptoService,
) {

    private val newSessionListener = object : NewSessionListener {
        override fun onNewSession(roomId: String?, sessionId: String) {
            synchronized(unknownSessionsFailure) {
                unknownSessionsFailure[sessionId]
                        ?.toList()
                        .orEmpty()
                        .also {
                            unknownSessionsFailure[sessionId]?.clear()
                        }
            }.forEach {
                requestDecryption(it)
            }
        }
    }

    private var executor: ExecutorService? = null

    // Set of eventIds which are currently decrypting
    private val existingRequests = mutableSetOf<DecryptionRequest>()

    // sessionId -> list of eventIds
    private val unknownSessionsFailure = mutableMapOf<String, MutableSet<DecryptionRequest>>()

    fun start() {
        executor = Executors.newSingleThreadExecutor()
        cryptoService.addNewSessionListener(newSessionListener)
    }

    fun destroy() {
        cryptoService.removeSessionListener(newSessionListener)
        executor?.shutdownNow()
        executor = null
        synchronized(unknownSessionsFailure) {
            unknownSessionsFailure.clear()
        }
        synchronized(existingRequests) {
            existingRequests.clear()
        }
    }

    fun requestDecryption(request: DecryptionRequest) {
        synchronized(unknownSessionsFailure) {
            for (requests in unknownSessionsFailure.values) {
                if (request in requests) {
                    Timber.d("Skip Decryption request for event ${request.event.eventId}, unknown session")
                    return
                }
            }
        }
        synchronized(existingRequests) {
            if (!existingRequests.add(request)) {
                Timber.d("Skip Decryption request for event ${request.event.eventId}, already requested")
                return
            }
        }
        executor?.execute {
            try {
                processDecryptRequest(request)
            } catch (e: InterruptedException) {
                Timber.i("Decryption got interrupted")
            }
        }
    }

    private fun processDecryptRequest(request: DecryptionRequest) {
        val event = request.event
        val timelineId = request.timelineId

        // Non-encrypted events were only made thread-aware here; that decoration runs in the SQL sync
        // path now, so there is nothing to do for them on-demand.
        if (!event.isEncrypted()) return

        try {
            val result = runBlocking {
                cryptoService.decryptEvent(event, timelineId)
            }
            Timber.v("Successfully decrypted event ${event.eventId}")
            val eventId = event.eventId ?: return
            runBlocking {
                database.awaitDbTransaction(dispatcher) {
                    stores.event.applyDecryptionResult(eventId, result)
                    // the event can now be aggregated (reactions/edits) on its clear content
                    stores.eventInsert.setCanBeProcessed(eventId, true)
                }
            }
        } catch (e: MXCryptoError) {
            Timber.v("Failed to decrypt event ${event.eventId} : ${e.localizedMessage}")
            if (e is MXCryptoError.Base) {
                val eventId = event.eventId.orEmpty()
                runBlocking {
                    database.awaitDbTransaction(dispatcher) {
                        stores.event.applyDecryptionError(
                                eventId,
                                e.errorType.name,
                                e.technicalMessage.takeIf { it.isNotEmpty() } ?: e.detailedErrorDescription,
                        )
                    }
                }
                event.content?.toModel<EncryptedEventContent>()?.let { content ->
                    content.sessionId?.let { sessionId ->
                        synchronized(unknownSessionsFailure) {
                            unknownSessionsFailure.getOrPut(sessionId) { mutableSetOf() }.add(request)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Timber.e("Failed to decrypt event ${event.eventId}, ${t.localizedMessage}")
        } finally {
            synchronized(existingRequests) {
                existingRequests.remove(request)
            }
        }
    }

    data class DecryptionRequest(
            val event: Event,
            val timelineId: String
    )
}
