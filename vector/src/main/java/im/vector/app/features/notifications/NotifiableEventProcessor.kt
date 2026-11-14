/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.notifications

import im.vector.app.features.invite.AutoAcceptInvites
import im.vector.app.features.notifications.ProcessedEvent.Type.KEEP
import im.vector.app.features.notifications.ProcessedEvent.Type.REMOVE
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.read.ReadService
import timber.log.Timber
import javax.inject.Inject

private typealias ProcessedEvents = List<ProcessedEvent<NotifiableEvent>>

class NotifiableEventProcessor @Inject constructor(
        private val outdatedDetector: OutdatedEventDetector,
        private val autoAcceptInvites: AutoAcceptInvites
) {

    fun process(
            queuedEvents: List<NotifiableEvent>,
            currentRoomId: String?,
            currentThreadId: String?,
            renderedEvents: ProcessedEvents,
            changedReceipts: Set<Pair<String, String>>? = null,
    ): ProcessedEvents {
        val previouslyChecked = renderedEvents.filter { it.type == KEEP }.mapTo(HashSet()) { it.event }
        val toCheck = queuedEvents.filterIsInstance<NotifiableMessageEvent>().filter { event ->
            !event.shouldIgnoreMessageEventInRoom(currentRoomId, currentThreadId) &&
                    (changedReceipts == null || event !in previouslyChecked ||
                            (event.roomId to (event.threadId ?: ReadService.THREAD_ID_MAIN)) in changedReceipts)
        }
        val readEventIds = if (toCheck.isEmpty()) emptySet() else outdatedDetector.readEventIds(toCheck)
        val processedEvents = queuedEvents.map { event ->
            val type = when (event) {
                is InviteNotifiableEvent -> if (autoAcceptInvites.hideInvites) REMOVE else KEEP
                is NotifiableMessageEvent -> when {
                    event.shouldIgnoreMessageEventInRoom(currentRoomId, currentThreadId) -> REMOVE
                            .also { Timber.d("notification message removed due to currently viewing the same room or thread") }
                    event.eventId in readEventIds -> REMOVE
                            .also {
                                DebugLog.i {
                                    "NOTIFDBG Removing read notification event=${event.eventId} " +
                                            "room=${event.roomId} thread=${event.threadId}"
                                }
                            }
                    else -> KEEP
                }
                is SimpleNotifiableEvent -> when (event.type) {
                    EventType.REDACTION -> REMOVE
                    else -> KEEP
                }
            }
            ProcessedEvent(type, event)
        }

        val queuedIds = queuedEvents.mapTo(HashSet()) { it.eventId }
        val removedEventsDiff = renderedEvents.filter { renderedEvent ->
            renderedEvent.type == KEEP && renderedEvent.event.eventId !in queuedIds
        }.map { ProcessedEvent(REMOVE, it.event) }

        return removedEventsDiff + processedEvents
    }
}
