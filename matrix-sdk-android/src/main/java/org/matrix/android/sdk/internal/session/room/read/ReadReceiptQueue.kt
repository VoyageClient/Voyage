/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.read

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.SessionLifecycleObserver
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.di.SessionId
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.platform.KeyValueStoreFactory
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.task.TaskExecutor
import timber.log.Timber
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

private const val READ_MARKER = "m.fully_read"
private const val BASE_DELAY_MS = 2_000L
private const val MAX_DELAY_MS = 15 * 60 * 1000L // eventually retry at most every 15 minutes

@JsonClass(generateAdapter = true)
internal data class PendingReadReceipt(
        @Json(name = "roomId") val roomId: String,
        @Json(name = "fullyReadEventId") val fullyReadEventId: String? = null,
        @Json(name = "readReceiptEventId") val readReceiptEventId: String? = null,
        @Json(name = "readReceiptType") val readReceiptType: String,
        @Json(name = "threadId") val threadId: String? = null,
)

/**
 * Persistent, off-main-thread queue of read receipts / fully-read markers that retries (with exponential
 * backoff capped at 15 minutes) until the homeserver confirms the send. Entries are only removed once the
 * request succeeds, and the queue survives app restarts so reads performed while offline are eventually
 * synced. One pending entry is kept per room; a newer read supersedes the older one.
 */
@SessionScope
internal class ReadReceiptQueue @Inject constructor(
        storeFactory: KeyValueStoreFactory,
        @SessionId sessionId: String,
        private val roomApi: RoomAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
        private val taskExecutor: TaskExecutor,
) : SessionLifecycleObserver {

    private val storage = storeFactory.create("ReadReceiptQueue_$sessionId")
    private val adapter = MoshiProvider.providesMoshi().adapter(PendingReadReceipt::class.java)

    // roomId -> latest pending read for that room
    private val pending = mutableMapOf<String, PendingReadReceipt>()
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private var loopJob: Job? = null

    fun enqueue(
            roomId: String,
            fullyReadEventId: String?,
            readReceiptEventId: String?,
            readReceiptType: String,
            threadId: String?,
    ) {
        if (fullyReadEventId == null && readReceiptEventId == null) return
        val entry = PendingReadReceipt(roomId, fullyReadEventId, readReceiptEventId, readReceiptType, threadId)
        synchronized(pending) {
            pending[roomId] = entry
            persist()
        }
        wakeUp.trySend(Unit)
    }

    override fun onSessionStarted(session: Session) {
        synchronized(pending) {
            if (pending.isEmpty()) {
                restore()
            }
        }
        if (loopJob?.isActive != true) {
            loopJob = taskExecutor.executorScope.launch {
                runLoop()
            }
        }
        wakeUp.trySend(Unit)
    }

    override fun onSessionStopped(session: Session) {
        loopJob?.cancel()
        loopJob = null
    }

    private suspend fun runLoop() {
        var backoff = BASE_DELAY_MS
        while (coroutineContext.isActive) {
            val snapshot = synchronized(pending) { pending.values.toList() }
            if (snapshot.isEmpty()) {
                backoff = BASE_DELAY_MS
                wakeUp.receive()
                continue
            }
            var anyFailed = false
            for (entry in snapshot) {
                if (send(entry)) {
                    synchronized(pending) {
                        // Only drop it if it wasn't superseded by a newer read while sending.
                        if (pending[entry.roomId] == entry) {
                            pending.remove(entry.roomId)
                            persist()
                        }
                    }
                } else {
                    anyFailed = true
                }
            }
            if (!anyFailed) {
                backoff = BASE_DELAY_MS
                continue
            }
            val wokenEarly = withTimeoutOrNull(backoff) { wakeUp.receive() } != null
            backoff = if (wokenEarly) BASE_DELAY_MS else (backoff * 2).coerceAtMost(MAX_DELAY_MS)
        }
    }

    private suspend fun send(entry: PendingReadReceipt): Boolean {
        return try {
            // canRetry = false: this queue owns the retry/backoff policy.
            executeRequest(globalErrorReceiver, canRetry = false) {
                val markers = mutableMapOf<String, String>()
                entry.fullyReadEventId?.let { markers[READ_MARKER] = it }
                entry.readReceiptEventId?.let { markers[entry.readReceiptType] = it }
                if (markers[READ_MARKER] == null) {
                    entry.readReceiptEventId?.let {
                        roomApi.sendReceipt(entry.roomId, entry.readReceiptType, it, ReadBody(threadId = entry.threadId))
                    }
                } else {
                    roomApi.sendReadMarker(entry.roomId, markers)
                }
            }
            true
        } catch (failure: Throwable) {
            // 403 = we're not in the room (kicked/banned); the receipt can never land, drop it.
            if (failure is Failure.ServerError && failure.error.code == MatrixError.M_FORBIDDEN) {
                Timber.w("ReadReceiptQueue: receipt for ${entry.roomId} refused, dropping")
                return true
            }
            Timber.w(failure, "ReadReceiptQueue: failed to send read receipt for ${entry.roomId}, will retry")
            false
        }
    }

    private fun persist() {
        val set = pending.values.map { adapter.toJson(it) }.toSet()
        storage.putStringSet(PERSISTENCE_KEY, set)
    }

    private fun restore() {
        storage.getStringSet(PERSISTENCE_KEY)?.forEach { json ->
            tryOrNull { adapter.fromJson(json) }?.let { pending[it.roomId] = it }
        }
    }

    companion object {
        private const val PERSISTENCE_KEY = "PendingReadReceipts"
    }
}
