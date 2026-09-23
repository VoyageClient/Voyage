/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.internal.session.SessionScope
import java.util.Collections
import javax.inject.Inject

/**
 * Lets a caller wait for one local echo's send to settle before firing the next, so a bulk resend
 * cannot let a later message overtake an earlier one that is still retrying.
 */
@SessionScope
internal class SendOutcomeTracker @Inject constructor() {

    private val waiters = Collections.synchronizedMap(HashMap<String, CompletableDeferred<SendState>>())
    private val roomsResending = Collections.synchronizedSet(HashSet<String>())

    /** Must be called before the send is posted, otherwise its outcome can land before anyone listens. */
    fun track(eventId: String) {
        waiters[eventId] = CompletableDeferred()
    }

    fun untrack(eventId: String) {
        waiters.remove(eventId)
    }

    /**
     * Suspends until the echo reaches a terminal send state. [SendState.UNKNOWN] means it is gone
     * (cancelled, or never tracked); null means the wait timed out with the send still pending.
     */
    suspend fun await(eventId: String, timeoutMs: Long): SendState? {
        val waiter = waiters[eventId] ?: return SendState.UNKNOWN
        return try {
            withTimeoutOrNull(timeoutMs) { waiter.await() }
        } finally {
            waiters.remove(eventId)
        }
    }

    fun onSendStateChanged(eventId: String, sendState: SendState) {
        if (sendState.isSent() || sendState.hasFailed() || sendState == SendState.UNKNOWN) {
            // Left in the map for [await] to take: a send can settle before the caller gets to await it,
            // and dropping the entry here would report that outcome as "never tracked".
            waiters[eventId]?.complete(sendState)
        }
    }

    fun onEchoDeleted(eventId: String) = onSendStateChanged(eventId, SendState.UNKNOWN)

    /** False when a resend batch is already running for this room, so a second tap does not double-send. */
    fun startResendBatch(roomId: String): Boolean = roomsResending.add(roomId)

    fun endResendBatch(roomId: String) {
        roomsResending.remove(roomId)
    }
}
