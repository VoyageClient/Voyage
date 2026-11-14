/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.notifications

import timber.log.Timber
import java.util.Collections
import java.util.IdentityHashMap

data class NotificationEventQueue(
        private val queue: MutableList<NotifiableEvent>,

        // Retain dismissed push IDs until sync catches up, so they cannot reappear.
        private val seenEventIds: CircularCache<String>
) {

    init {
        trim()
    }

    private fun trim() {
        val roomCounts = mutableMapOf<String, Int>()
        val retained = queue.asReversed().filter { event ->
            event !is NotifiableMessageEvent || roomCounts.getOrPut(event.roomId) { 0 }.let { count ->
                roomCounts[event.roomId] = count + 1
                count < MAX_EVENTS_PER_ROOM
            }
        }.take(MAX_EVENTS).asReversed()
        queue.clear()
        queue.addAll(retained)
    }

    companion object {
        const val MAX_EVENTS = 200
        const val MAX_EVENTS_PER_ROOM = 20
    }

    fun markRedacted(eventIds: List<String>) {
        eventIds.forEach { redactedId ->
            queue.replace(redactedId) {
                when (it) {
                    is InviteNotifiableEvent -> it.copy(isRedacted = true)
                    is NotifiableMessageEvent -> it.copy(isRedacted = true)
                    is SimpleNotifiableEvent -> it.copy(isRedacted = true)
                }
            }
        }
    }

    fun syncRoomEvents(roomsLeft: Collection<String>, roomsJoined: Collection<String>) {
        if (roomsLeft.isNotEmpty() || roomsJoined.isNotEmpty()) {
            queue.removeAll {
                when (it) {
                    is NotifiableMessageEvent -> roomsLeft.contains(it.roomId)
                    is InviteNotifiableEvent -> roomsLeft.contains(it.roomId) || roomsJoined.contains(it.roomId)
                    else -> false
                }
            }
        }
    }

    fun isEmpty() = queue.isEmpty()

    fun clearAndAdd(events: List<NotifiableEvent>) {
        queue.clear()
        queue.addAll(events)
        trim()
    }

    /** Removes by identity, so an event replaced or re-added since [events] were read is kept. */
    fun removeInstances(events: Collection<NotifiableEvent>) {
        val toRemove = Collections.newSetFromMap(IdentityHashMap<NotifiableEvent, Boolean>()).apply { addAll(events) }
        queue.removeAll { it in toRemove }
    }

    fun clear() {
        queue.clear()
    }

    fun reset() {
        clear()
        seenEventIds.clear()
    }

    fun add(notifiableEvent: NotifiableEvent) {
        val existing = findExistingById(notifiableEvent)
        val edited = findEdited(notifiableEvent)
        when {
            existing != null -> {
                if (existing.canBeReplaced) {
                    // Sync can replace a push placeholder with decrypted content without alerting again.
                    replace(replace = existing, with = notifiableEvent)
                }
            }
            edited != null -> {
                replace(replace = edited, with = notifiableEvent)
            }
            seenEventIds.contains(notifiableEvent.eventId) -> {
                Timber.d("onNotifiableEventReceived(): skipping event, already seen")
            }
            else -> {
                seenEventIds.put(notifiableEvent.eventId)
                queue.add(notifiableEvent)
            }
        }
        trim()
    }

    private fun findExistingById(notifiableEvent: NotifiableEvent): NotifiableEvent? {
        return queue.firstOrNull { it.eventId == notifiableEvent.eventId }
    }

    private fun findEdited(notifiableEvent: NotifiableEvent): NotifiableEvent? {
        return notifiableEvent.editedEventId?.let { editedId ->
            queue.firstOrNull {
                it.eventId == editedId || it.editedEventId == editedId
            }
        }
    }

    private fun replace(replace: NotifiableEvent, with: NotifiableEvent) {
        queue.remove(replace)
        queue.add(
                when (with) {
                    is InviteNotifiableEvent -> with.copy(isUpdated = true)
                    is NotifiableMessageEvent -> with.copy(isUpdated = true)
                    is SimpleNotifiableEvent -> with.copy(isUpdated = true)
                }
        )
    }

    fun clearMemberShipNotificationForRoom(roomId: String) {
        Timber.d("clearMemberShipOfRoom $roomId")
        queue.removeAll { it is InviteNotifiableEvent && it.roomId == roomId }
    }

    fun clearMessagesForRoom(roomId: String) {
        Timber.d("clearMessageEventOfRoom $roomId")
        queue.removeAll { it is NotifiableMessageEvent && it.roomId == roomId }
    }

    fun clearMessagesForThread(roomId: String, threadId: String) {
        Timber.d("clearMessageEventOfThread $roomId, $threadId")
        queue.removeAll { it is NotifiableMessageEvent && it.roomId == roomId && it.threadId == threadId }
    }

    fun rawEvents(): List<NotifiableEvent> = queue
}

private fun MutableList<NotifiableEvent>.replace(eventId: String, block: (NotifiableEvent) -> NotifiableEvent) {
    val indexToReplace = indexOfFirst { it.eventId == eventId }
    if (indexToReplace == -1) {
        return
    }
    set(indexToReplace, block(get(indexToReplace)))
}
