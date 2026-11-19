/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.sliding

import org.matrix.android.sdk.internal.session.SessionScope
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject

@SessionScope
internal class SlidingSyncRoomSubscriptions @Inject constructor() {

    /** How much of a room a subscription asks for. */
    enum class Depth {
        /** The room the user is reading: enough timeline to open on. */
        OPEN,

        /** A room on screen in the list: enough to keep its preview and unread count honest. */
        VISIBLE,
    }

    private val openRooms = LinkedHashMap<String, Int>()
    private val visibleRooms = LinkedHashMap<String, Int>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** The room a timeline is reading. Worth interrupting the current long poll for. */
    fun acquire(roomId: String) {
        val (changed, holders) = synchronized(openRooms) { openRooms.increment(roomId) to openRooms[roomId] }
        Timber.i("SYNCDBG subscription acquire $roomId holders=$holders entered=$changed open=${openRoomIds()} listeners=${listeners.size}")
        if (changed) listeners.forEach { it() }
    }

    fun release(roomId: String) {
        val (changed, holders) = synchronized(openRooms) { openRooms.decrement(roomId) to openRooms[roomId] }
        Timber.i("SYNCDBG subscription release $roomId holders=${holders ?: 0} left=$changed open=${openRoomIds()} listeners=${listeners.size}")
        if (changed) listeners.forEach { it() }
    }

    private fun openRoomIds() = synchronized(openRooms) { openRooms.keys.toList() }

    /**
     * The room-list viewport. Deliberately silent: these rooms are a prefetch, and cancelling the
     * in-flight sync for every scroll settle would cost a round trip per flick of the list.
     */
    fun update(added: Set<String>, removed: Set<String>) {
        if (added.isEmpty() && removed.isEmpty()) return
        synchronized(visibleRooms) {
            removed.forEach { visibleRooms.decrement(it) }
            added.forEach { visibleRooms.increment(it) }
        }
    }

    fun snapshot(): Map<String, Depth> {
        val open = synchronized(openRooms) { openRooms.keys.toList() }
        val visible = synchronized(visibleRooms) { visibleRooms.keys.toList() }
        return buildMap {
            visible.forEach { put(it, Depth.VISIBLE) }
            open.forEach { put(it, Depth.OPEN) }
        }
    }

    /** Something else that shapes the request changed; worth interrupting the current long poll for. */
    fun invalidate() {
        Timber.i("SYNCDBG subscriptions invalidated listeners=${listeners.size}")
        listeners.forEach { it() }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** @return whether the room entered the set. */
    private fun MutableMap<String, Int>.increment(roomId: String): Boolean {
        val count = this[roomId] ?: 0
        this[roomId] = count + 1
        return count == 0
    }

    /** @return whether the room left the set. */
    private fun MutableMap<String, Int>.decrement(roomId: String): Boolean {
        val count = this[roomId] ?: return false
        if (count == 1) remove(roomId) else this[roomId] = count - 1
        return count == 1
    }
}
