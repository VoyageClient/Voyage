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
import org.matrix.android.sdk.api.session.crypto.model.MXEventDecryptionResult
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.content.EncryptedEventContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.room.summary.RoomSummaryPreviewInvalidation
import org.matrix.android.sdk.internal.session.search.index.EventIndexer
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

internal class TimelineEventDecryptor @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val cryptoService: CryptoService,
        private val previewInvalidation: RoomSummaryPreviewInvalidation,
        private val eventIndexer: EventIndexer,
) {

    private val newSessionListener = object : NewSessionListener {
        override fun onNewSession(roomId: String?, sessionId: String) {
            val retry = synchronized(unknownSessionsFailure) {
                unknownSessionsFailure[sessionId]
                        ?.toList()
                        .orEmpty()
                        .also { unknownSessionsFailure[sessionId]?.clear() }
            }
            // Just removed from unknownSessionsFailure above, so skip the (O(n)) re-scan of that map.
            if (retry.isNotEmpty()) requestDecryption(retry, alreadyClearedFromUnknown = true)
            // The map above only holds events that failed IN THIS app run. A key import (exported/backup
            // keys) needs to also re-decrypt events that failed in a previous run and were persisted as
            // UTD — decryption otherwise only runs at sync/insert time. Re-scan the room's stored UTDs
            // once (per run) so old encrypted rooms decrypt after import.
            if (roomId != null && rescannedRooms.add(roomId)) {
                executor?.execute { rescanRoomForDecryption(roomId) }
            }
        }
    }

    // Rooms already re-scanned for persisted UTDs this run (dedupes the per-session storm of a bulk import).
    private val rescannedRooms = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun rescanRoomForDecryption(roomId: String) {
        val events = runBlocking {
            database.awaitDbTransaction(dispatcher) {
                stores.event.getUndecryptedEncryptedEvents(roomId, EventType.ENCRYPTED)
            }
        }
        requestDecryption(events.map { DecryptionRequest(it, "") })
    }

    private var executor: ExecutorService? = null

    // Notified after an event is successfully decrypted, so the owning timeline can rebuild its snapshot
    // (a decryption result is written to the event table, which the timeline_event flow doesn't observe).
    private val onDecryptedListeners = CopyOnWriteArrayList<OnEventDecryptedListener>()

    fun addOnDecryptedListener(listener: OnEventDecryptedListener) = onDecryptedListeners.add(listener)
    fun removeOnDecryptedListener(listener: OnEventDecryptedListener) = onDecryptedListeners.remove(listener)

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
        rescannedRooms.clear()
    }

    fun requestDecryption(request: DecryptionRequest, alreadyClearedFromUnknown: Boolean = false) =
            requestDecryption(listOf(request), alreadyClearedFromUnknown)

    fun requestDecryption(requests: List<DecryptionRequest>, alreadyClearedFromUnknown: Boolean = false) {
        val toProcess = ArrayList<DecryptionRequest>(requests.size)
        for (request in requests) {
            if (!alreadyClearedFromUnknown) {
                val knownUnknownSession = synchronized(unknownSessionsFailure) {
                    unknownSessionsFailure.values.any { request in it }
                }
                if (knownUnknownSession) continue
            }
            val added = synchronized(existingRequests) { existingRequests.add(request) }
            if (added) toProcess.add(request)
        }
        if (toProcess.isEmpty()) return
        executor?.execute {
            try {
                processDecryptRequests(toProcess)
            } catch (e: InterruptedException) {
                Timber.i("Decryption got interrupted")
            }
        }
    }

    private fun processDecryptRequests(requests: List<DecryptionRequest>) {
        try {
            // Chunk so a large backlog (e.g. a whole-room rescan) writes in bounded transactions that
            // surface progressively, rather than one giant commit that holds the write lock.
            requests.chunked(DECRYPT_BATCH_SIZE).forEach { processChunk(it) }
        } finally {
            synchronized(existingRequests) { existingRequests.removeAll(requests.toSet()) }
        }
    }

    // Decrypt the whole chunk first, then persist every result in a SINGLE transaction. Each transaction
    // commit fsyncs (no WAL), so a per-event transaction caps throughput at a handful/second — batching a
    // room's UTD backlog into one commit is what lets them surface together after a key import / room open.
    private fun processChunk(requests: List<DecryptionRequest>) {
        val successes = ArrayList<Pair<Event, MXEventDecryptionResult>>(requests.size)
        val errors = ArrayList<Triple<String, String, String?>>()
        for (request in requests) {
            val event = request.event
            // Defensive: only encrypted events have anything to decrypt here.
            if (!event.isEncrypted()) continue
            try {
                val result = runBlocking { cryptoService.decryptEvent(event, request.timelineId) }
                if (event.eventId != null) successes.add(event to result)
            } catch (e: MXCryptoError) {
                if (e is MXCryptoError.Base) {
                    errors.add(Triple(
                            event.eventId.orEmpty(),
                            e.errorType.name,
                            e.technicalMessage.takeIf { it.isNotEmpty() } ?: e.detailedErrorDescription,
                    ))
                    event.content?.toModel<EncryptedEventContent>()?.sessionId?.let { sessionId ->
                        synchronized(unknownSessionsFailure) {
                            unknownSessionsFailure.getOrPut(sessionId) { mutableSetOf() }.add(request)
                        }
                    }
                }
            } catch (t: Throwable) {
                Timber.e("Failed to decrypt event ${event.eventId}, ${t.localizedMessage}")
            }
        }
        if (successes.isNotEmpty() || errors.isNotEmpty()) {
            runBlocking {
                database.awaitDbTransaction(dispatcher) {
                    successes.forEach { (event, result) ->
                        val eventId = event.eventId.orEmpty()
                        stores.event.applyDecryptionResult(eventId, result)
                        // the event can now be aggregated (reactions/edits) on its clear content
                        stores.eventInsert.setCanBeProcessed(eventId, true)
                    }
                    errors.forEach { (eventId, code, reason) ->
                        stores.event.applyDecryptionError(eventId, code, reason)
                    }
                    // The room list won't see these event-table writes; refresh summaries they preview.
                    stores.roomSummary.roomIdsWithPreviewEvent(successes.map { it.first.eventId.orEmpty() }).forEach { roomId ->
                        previewInvalidation.onPreviewChanged(roomId)
                        stores.roomSummary.touch(roomId)
                    }
                }
            }
        }
        if (successes.isNotEmpty()) {
            eventIndexer.onEventsDecrypted(successes)
            onDecryptedListeners.forEach { it.onEventsDecrypted() }
        }
    }

    data class DecryptionRequest(
            val event: Event,
            val timelineId: String
    )

    fun interface OnEventDecryptedListener {
        fun onEventsDecrypted()
    }

    companion object {
        private const val DECRYPT_BATCH_SIZE = 100
    }
}
