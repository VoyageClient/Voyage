/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.render

import im.vector.app.core.di.ActiveSessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getUserOrDefault
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the sender of an event a permalink points at, so a message link can be pilled as
 * "Message from <sender>" with their avatar. The lookup is local-first; when the event isn't in the
 * store the sender is fetched from the server in the background and [Listener]s are told to
 * re-render once it lands.
 */
@Singleton
class PermalinkEventResolver @Inject constructor(
        private val sessionHolder: ActiveSessionHolder,
        private val coroutineScope: CoroutineScope,
) {

    data class Sender(val userId: String, val displayName: String?, val avatarUrl: String?)

    fun interface Listener {
        fun onSenderResolved(eventId: String)
    }

    private companion object {
        private const val CACHE_CAPACITY = 256
        private const val RETRY_INTERVAL_MS = 60_000L
    }

    private val senders = object : LinkedHashMap<String, Sender>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Sender>): Boolean = size > CACHE_CAPACITY
    }
    private val failures = mutableMapOf<String, Long>()
    private val inFlight = mutableSetOf<String>()
    private val listeners = CopyOnWriteArraySet<Listener>()

    fun addListener(listener: Listener) = listeners.add(listener)

    fun removeListener(listener: Listener) = listeners.remove(listener)

    /**
     * The sender of [eventId], or null while it is unknown — in which case a server lookup is started
     * and listeners are notified when it resolves.
     */
    fun getSender(roomId: String, eventId: String): Sender? {
        val key = "$roomId/$eventId"
        synchronized(senders) { senders[key] }?.let { return it }
        val session = sessionHolder.getSafeActiveSession() ?: return null
        val cachedSenderId = session.eventService().getEventFromCache(roomId, eventId)?.senderId
        if (cachedSenderId != null) {
            return session.toSender(roomId, cachedSenderId).also { synchronized(senders) { senders[key] = it } }
        }
        fetch(session, roomId, eventId, key)
        return null
    }

    private fun fetch(session: Session, roomId: String, eventId: String, key: String) {
        synchronized(senders) {
            val lastFailure = failures[key]
            if (lastFailure != null && System.currentTimeMillis() - lastFailure < RETRY_INTERVAL_MS) return
            if (!inFlight.add(key)) return
        }
        coroutineScope.launch(Dispatchers.IO) {
            val sender = tryOrNull { session.eventService().getEvent(roomId, eventId) }
                    ?.senderId
                    ?.let { session.toSender(roomId, it) }
            synchronized(senders) {
                inFlight.remove(key)
                if (sender == null) {
                    if (failures.size > CACHE_CAPACITY) failures.clear()
                    failures[key] = System.currentTimeMillis()
                } else {
                    failures.remove(key)
                    senders[key] = sender
                }
            }
            if (sender != null) {
                listeners.forEach { it.onSenderResolved(eventId) }
            }
        }
    }

    private fun Session.toSender(roomId: String, userId: String): Sender {
        val member = roomService().getRoomMember(userId, roomId)
        return if (member != null) {
            Sender(userId, member.displayName, member.avatarUrl)
        } else {
            val user = getUserOrDefault(userId)
            Sender(userId, user.displayName, user.avatarUrl)
        }
    }
}
