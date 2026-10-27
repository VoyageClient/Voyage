/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.pinned

import im.vector.app.features.home.room.detail.timeline.helper.SenderProfileResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.runningReduce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.getTimelineEvent
import org.matrix.android.sdk.api.session.room.model.RoomPinnedEventsContent
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.flow.flow
import timber.log.Timber
import javax.inject.Inject

class GetPinnedEventsUseCase @Inject constructor(
        private val session: Session,
        private val senderProfileResolver: SenderProfileResolver,
) {

    fun getPinnedEventIds(room: Room): Flow<List<String>> {
        return room.flow()
                .liveStateEvent(EventType.STATE_ROOM_PINNED_EVENT, QueryStringValue.IsEmpty)
                .distinctUntilChanged()
                .map { optional ->
                    optional.getOrNull()?.getClearContent().toModel<RoomPinnedEventsContent>()?.pinned.orEmpty().distinct()
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
                        observePinnedEvents(room, ids)
                    }
                }
    }

    private fun observePinnedEvents(room: Room, ids: List<String>): Flow<List<TimelineEvent>> = channelFlow {
        val fetchSlots = Semaphore(4)
        ids.forEach { eventId ->
            launch {
                fetchSlots.withPermit {
                    if (room.getTimelineEvent(eventId) == null) {
                        try {
                            session.eventService().ensureEventCached(room.roomId, eventId, requireTimelineEvent = true)
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            Timber.w(failure, "Failed to load pinned event %s in %s", eventId, room.roomId)
                        }
                    }
                }
            }
        }

        // Observe the cache independently: a slow fetch must not hold back other pins, and sync may
        // supply a missing event after its fetch failed.
        combine(ids.map { observePinnedEvent(room, it) }) { events ->
            events.filterNotNull().sortedBy { it.root.originServerTs ?: 0 }
        }
                .distinctUntilChanged()
                .collect { send(it) }
    }.flowOn(room.coroutineDispatchers.io)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observePinnedEvent(room: Room, eventId: String): Flow<TimelineEvent?> {
        return merge(
                room.flow().liveTimelineEvent(eventId).map { it.getOrNull() },
                session.eventService().decryptionUpdates(room.roomId).map { room.getTimelineEvent(eventId) },
        )
                // Timeline chunks can be evicted while the message remains pinned.
                .runningReduce { previous, current -> current ?: previous }
                .distinctUntilChanged()
                .map { event ->
                    event?.takeUnless { it.root.isRedacted() }?.let {
                        if (it.root.getClearType() == EventType.ENCRYPTED) {
                            session.eventService().requestDecryption(it.root)
                        }
                        it.copy(senderInfo = senderProfileResolver.resolve(room.roomId, it.senderInfo, false))
                    }
                }
    }
}
