/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import kotlinx.coroutines.sync.Mutex
import org.matrix.android.sdk.internal.session.SessionScope
import javax.inject.Inject

/**
 * Keeps each room's media going out in the order it was queued, although uploads run in parallel:
 * a send records the room's media echoes queued before it, and its dispatcher holds back until those
 * have left. In-memory only; after a restart the waits persisted in the task params fall back on the
 * echoes' send states.
 */
@SessionScope
internal class MediaSendOrder @Inject constructor() {

    /** Held across "are my predecessors out?" and posting the send, so two dispatchers can't interleave. */
    val dispatchLock = Mutex()

    private val undispatchedByRoom = HashMap<String, LinkedHashSet<String>>()

    /** Returns the media echoes of the same rooms still waiting to be dispatched, then queues [echoes] behind them. */
    fun enqueue(echoes: List<LocalEchoIdentifiers>): List<String> = synchronized(undispatchedByRoom) {
        val before = echoes.map { it.roomId }.distinct().flatMap { undispatchedByRoom[it].orEmpty() }
        echoes.forEach { undispatchedByRoom.getOrPut(it.roomId) { LinkedHashSet() }.add(it.eventId) }
        before
    }

    fun isUndispatched(eventId: String): Boolean = synchronized(undispatchedByRoom) {
        undispatchedByRoom.values.any { eventId in it }
    }

    fun markDispatched(eventIds: Collection<String>) = synchronized(undispatchedByRoom) {
        val ids = eventIds.toSet()
        undispatchedByRoom.values.forEach { it.removeAll(ids) }
        undispatchedByRoom.values.removeAll { it.isEmpty() }
    }
}
