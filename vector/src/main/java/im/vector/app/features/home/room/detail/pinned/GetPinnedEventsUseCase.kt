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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.Room
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
        // Timeline chunks can be evicted while the message remains pinned, so keep the last copy seen.
        val loaded = HashMap<String, TimelineEvent>()
        val decryptRequested = HashSet<String>()
        var fetchStarted = false
        // Sync may still supply a pin whose fetch failed, so the cache stays observed throughout.
        room.timelineService().getTimelineEventsFlow(ids).collect { stored ->
            stored.forEach { loaded[it.eventId] = it }
            if (!fetchStarted) {
                fetchStarted = true
                val fetchSlots = Semaphore(4)
                // Last pinned first: usually the newest message, which the banner opens on.
                ids.filterNot { it in loaded }.asReversed().forEach { eventId ->
                    launch { fetchSlots.withPermit { fetch(room, eventId) } }
                }
            }
            stored.forEach { event ->
                if (event.root.getClearType() == EventType.ENCRYPTED && decryptRequested.add(event.eventId)) {
                    launch { decrypt(event) }
                }
            }
            val profiles = senderProfileResolver.newSession()
            send(
                    ids.mapNotNull { loaded[it] }
                            .filterNot { it.root.isRedacted() }
                            .sortedBy { it.root.originServerTs ?: 0 }
                            .map { it.copy(senderInfo = profiles.resolve(room.roomId, it.senderInfo, false)) }
            )
        }
    }
            .distinctUntilChanged()
            .flowOn(room.coroutineDispatchers.io)

    private suspend fun fetch(room: Room, eventId: String) {
        try {
            session.eventService().ensureEventCached(room.roomId, eventId, requireTimelineEvent = true)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            Timber.w(failure, "Failed to load pinned event %s in %s", eventId, room.roomId)
        }
    }

    private suspend fun decrypt(event: TimelineEvent) {
        try {
            session.eventService().decryptEventNow(event.root)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            Timber.w(failure, "Failed to decrypt pinned event %s", event.eventId)
        }
    }
}
