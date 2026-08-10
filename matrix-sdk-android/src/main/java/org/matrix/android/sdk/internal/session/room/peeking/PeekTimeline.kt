/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.peeking

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * Read-only live Timeline over a peeked world-readable room, mimicking Element Web:
 * one v1 room initialSync for state + recent messages, then a v1 /events long-poll for
 * updates, with /messages for backward pagination. Everything stays in memory in the
 * shared [PeekedRoomDataSource]; nothing is persisted until the user actually joins.
 */
internal class PeekTimeline(
        private val dataSource: PeekedRoomDataSource,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val peekRoomInitialSyncTask: PeekRoomInitialSyncTask,
        private val peekLiveEventsTask: PeekLiveEventsTask,
        private val peekRoomMessagesTask: PeekRoomMessagesTask,
) : Timeline {

    companion object {
        private const val INITIAL_SYNC_LIMIT = 30
        private const val INITIAL_SYNC_TIMEOUT_MS = 20_000L
        private const val PAGINATION_PAGE_SIZE = 30
        private const val LONG_POLL_TIMEOUT_MS = 30_000L
        private const val ERROR_RETRY_DELAY_MS = 15_000L
    }

    override val timelineID = UUID.randomUUID().toString()
    override val isLive = true

    private val scope = CoroutineScope(SupervisorJob() + coroutineDispatchers.io)
    private val listeners = CopyOnWriteArrayList<Timeline.Listener>()
    private val started = AtomicBoolean(false)
    @Volatile var isDisposed = false
        private set

    @Volatile private var backwardState = Timeline.PaginationState(hasMoreToLoad = true)
    private val forwardState = Timeline.PaginationState(hasMoreToLoad = false)

    // Guarded by dataSource.mutex.
    private var backwardToken: String? = null
    private var backPaginationExhausted = false
    private var paginating = false

    // A paginate arriving before the initialSync response must not conclude "no more history"
    // from the still-null token; the loader retriggers it once the seed snapshot is in.
    @Volatile private var initialLoaded = false

    override fun addListener(listener: Timeline.Listener): Boolean = listeners.add(listener)

    override fun removeListener(listener: Timeline.Listener): Boolean = listeners.remove(listener)

    override fun removeAllListeners() = listeners.clear()

    override fun start(rootThreadEventId: String?) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            dataSource.timelineFlow.collect { snapshot ->
                withContext(coroutineDispatchers.main) {
                    listeners.forEach { tryOrNull { it.onTimelineUpdated(snapshot) } }
                }
            }
        }
        scope.launch {
            val snapshot = try {
                // /initialSync can be pathologically slow on some servers (it's deprecated for a
                // reason); don't leave the user staring at a spinner longer than this.
                withTimeout(INITIAL_SYNC_TIMEOUT_MS) {
                    peekRoomInitialSyncTask.execute(PeekRoomInitialSyncTask.Params(dataSource.roomId, INITIAL_SYNC_LIMIT))
                }
            } catch (failure: Throwable) {
                Timber.w(failure, "Room peek initialSync failed")
                updateBackwardState { it.copy(inError = true) }
                withContext(coroutineDispatchers.main) {
                    listeners.forEach { tryOrNull { it.onTimelineFailure(failure) } }
                }
                return@launch
            }
            dataSource.mutex.withLock {
                snapshot.stateEvents.forEach { dataSource.applyStateEvent(it) }
                backwardToken = snapshot.backwardToken
                initialLoaded = true
                // Some servers return the chunk newest-first; ingest chronologically. Majority vote
                // over adjacent pairs, so one clock-skewed federated event can't flip the whole chunk.
                val chunk = snapshot.timelineEvents
                val descendingPairs = chunk.zipWithNext().count { (a, b) -> (a.originServerTs ?: 0) > (b.originServerTs ?: 0) }
                val chronological = if (descendingPairs > chunk.size / 2) chunk.reversed() else chunk
                chronological.forEach { dataSource.appendEvent(it) }
                dataSource.publish()
            }
            longPollLoop()
        }
    }

    private suspend fun longPollLoop() {
        var from: String? = null
        while (coroutineContext.isActive) {
            val batch = try {
                peekLiveEventsTask.execute(PeekLiveEventsTask.Params(dataSource.roomId, from, LONG_POLL_TIMEOUT_MS))
            } catch (failure: Throwable) {
                Timber.w(failure, "Room peek /events poll failed, retrying")
                if (failure is Failure.ServerError) {
                    // The stream token can expire (server restart/purge); retrying it would fail
                    // forever. Re-anchor at the live edge — the id-keyed ingest dedupes any overlap.
                    from = null
                }
                delay(ERROR_RETRY_DELAY_MS)
                continue
            }
            if (from == null) {
                // Events sent between the initialSync and the first poll position would otherwise
                // be lost: backfill from the first live position down into the known window.
                batch.startToken?.let { fillGapBefore(it) }
            }
            from = batch.nextToken ?: from
            if (batch.events.isEmpty()) continue
            dataSource.mutex.withLock {
                batch.events.forEach { event ->
                    if (event.stateKey != null) dataSource.applyStateEvent(event)
                    dataSource.appendEvent(event)
                }
                dataSource.publish()
            }
            val newEventIds = batch.events.mapNotNull { it.eventId }
            withContext(coroutineDispatchers.main) {
                listeners.forEach { tryOrNull { it.onNewTimelineEvents(newEventIds) } }
            }
        }
    }

    private suspend fun fillGapBefore(liveToken: String) {
        val page = try {
            peekRoomMessagesTask.execute(PeekRoomMessagesTask.Params(dataSource.roomId, liveToken, INITIAL_SYNC_LIMIT))
        } catch (failure: Throwable) {
            Timber.w(failure, "Room peek gap backfill failed")
            return
        }
        dataSource.mutex.withLock {
            page.stateEvents.forEach { dataSource.applyStateEvent(it, onlyIfAbsent = true) }
            // Reverse-chronological page, appended oldest-first; already-known events keep their slot.
            page.events.asReversed().forEach { dataSource.appendEvent(it) }
            dataSource.publish()
        }
    }

    override fun dispose() {
        isDisposed = true
        scope.cancel()
    }

    override fun restartWithEventId(eventId: String?) {
        // Peeked timelines cannot jump to an arbitrary event; the live edge is always shown.
    }

    override suspend fun restartAtRoomStart(targetEventId: String?): String? = null

    override fun hasMoreToLoad(direction: Timeline.Direction): Boolean {
        return getPaginationState(direction).hasMoreToLoad
    }

    override fun paginate(direction: Timeline.Direction, count: Int) {
        if (direction == Timeline.Direction.FORWARDS) return
        scope.launch { paginateBackwards(count) }
    }

    override suspend fun awaitPaginate(direction: Timeline.Direction, count: Int): List<TimelineEvent> {
        if (direction == Timeline.Direction.BACKWARDS) {
            paginateBackwards(count)
        }
        return getSnapshot()
    }

    private suspend fun paginateBackwards(count: Int) {
        val token: String
        dataSource.mutex.withLock {
            if (paginating || backPaginationExhausted) return
            token = backwardToken ?: run {
                if (initialLoaded) {
                    backPaginationExhausted = true
                    updateBackwardState { it.copy(hasMoreToLoad = false) }
                }
                return
            }
            paginating = true
        }
        updateBackwardState { it.copy(loading = true, inError = false) }
        try {
            val page = try {
                peekRoomMessagesTask.execute(PeekRoomMessagesTask.Params(dataSource.roomId, token, count.coerceAtLeast(PAGINATION_PAGE_SIZE)))
            } catch (failure: Throwable) {
                Timber.w(failure, "Room peek backward pagination failed")
                updateBackwardState { it.copy(inError = true) }
                return
            }
            dataSource.mutex.withLock {
                // Historical lazy-loaded members must not clobber a member's current profile.
                page.stateEvents.forEach { dataSource.applyStateEvent(it, onlyIfAbsent = true) }
                backwardToken = page.nextToken
                if (page.events.isEmpty() || page.nextToken == null) {
                    backPaginationExhausted = true
                }
                val plainEvents = page.events.mapNotNull { dataSource.ingest(it) }
                if (plainEvents.isNotEmpty()) {
                    dataSource.prependEvents(plainEvents.asReversed())
                }
                dataSource.publish()
            }
        } finally {
            val exhausted = dataSource.mutex.withLock {
                paginating = false
                backPaginationExhausted
            }
            updateBackwardState { it.copy(loading = false, hasMoreToLoad = !exhausted) }
        }
    }

    private suspend fun updateBackwardState(updater: (Timeline.PaginationState) -> Timeline.PaginationState) {
        val newState = updater(backwardState)
        if (newState == backwardState) return
        backwardState = newState
        withContext(coroutineDispatchers.main) {
            listeners.forEach { tryOrNull { it.onStateUpdated(Timeline.Direction.BACKWARDS, newState) } }
        }
    }

    override fun getIndexOfEvent(eventId: String?): Int? {
        eventId ?: return null
        return getSnapshot().indexOfFirst { it.eventId == eventId }.takeIf { it >= 0 }
    }

    override fun getPaginationState(direction: Timeline.Direction): Timeline.PaginationState {
        return if (direction == Timeline.Direction.BACKWARDS) backwardState else forwardState
    }

    override fun getSnapshot(): List<TimelineEvent> = dataSource.timelineFlow.value
}
