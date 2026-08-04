/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.pinned

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.getTimelineEvent
import org.matrix.android.sdk.api.session.room.model.RoomPinnedEventsContent
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.flow.flow
import javax.inject.Inject

class GetPinnedEventsUseCase @Inject constructor(
        private val session: Session,
) {

    fun getPinnedEventIds(room: Room): Flow<List<String>> {
        return room.flow()
                .liveStateEvent(EventType.STATE_ROOM_PINNED_EVENT, QueryStringValue.IsEmpty)
                .map { optional ->
                    optional.getOrNull()?.content.toModel<RoomPinnedEventsContent>()?.pinned.orEmpty()
                }
                .distinctUntilChanged()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun execute(room: Room): Flow<List<TimelineEvent>> {
        return getPinnedEventIds(room)
                .flatMapLatest { ids ->
                    if (ids.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        // Re-resolve whenever something in the room gets decrypted, so pins stop
                        // showing "Encrypted message" once their keys arrive.
                        session.eventService().decryptionUpdates(room.roomId)
                                .onStart { emit(Unit) }
                                .map { resolve(room, ids) }
                    }
                }
    }

    private suspend fun resolve(room: Room, ids: List<String>): List<TimelineEvent> = withContext(Dispatchers.IO) {
        ids.mapNotNull { eventId ->
            tryOrNull { session.eventService().ensureEventCached(room.roomId, eventId, requireTimelineEvent = true) }
            room.getTimelineEvent(eventId)
                    ?.takeUnless { it.root.isRedacted() }
                    ?.also { event ->
                        if (event.root.getClearType() == EventType.ENCRYPTED) {
                            session.eventService().requestDecryption(event.root)
                        }
                    }
        }
                // Order by the pinned event's own date (oldest first), not by when it was pinned.
                .sortedBy { it.root.originServerTs ?: 0 }
    }
}
