/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.events.model.getRootThreadEventId
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.TimelineSettings
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.session.room.relation.threads.DefaultFetchThreadTimelineTask
import org.matrix.android.sdk.internal.session.room.relation.threads.FetchThreadTimelineTask
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * SQLDelight [Timeline] replacing DefaultTimeline+LoadTimelineStrategy+TimelineChunk's Realm
 * incremental-changeset model with snapshot rebuild. The snapshot is the loaded chunks' events
 * (newest-first), plus — when the live edge is loaded — the room's sending (local-echo) events on top.
 *
 * Seeding: a thread timeline seeds from the thread chunk; a permalink ([initialEventId]) seeds from the
 * chunk containing that event; otherwise the room's forward chunk (live). Backward pagination walks
 * prev_chunk_id then prev_token (server fetch); forward pagination (for a permalink scrolling toward
 * live) walks next_chunk_id then next_token.
 */
internal class SqlTimeline(
        private val roomId: String,
        private val initialEventId: String?,
        private val settings: TimelineSettings,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val stores: SessionStores,
        private val snapshotLoader: SqlChunkSnapshotLoader,
        private val paginationTask: PaginationTask,
        private val fetchThreadTimelineTask: FetchThreadTimelineTask,
        private val contextOfEventTask: GetContextOfEventTask,
        private val database: SessionSqlDatabase,
        private val sessionDispatcher: CoroutineDispatcher,
        private val eventDecryptor: TimelineEventDecryptor,
) : Timeline {

    override val timelineID = UUID.randomUUID().toString()

    private val listeners = CopyOnWriteArrayList<Timeline.Listener>()
    private val isStarted = AtomicBoolean(false)
    private val forwardState = AtomicReference(Timeline.PaginationState(hasMoreToLoad = false))
    private val backwardState = AtomicReference(Timeline.PaginationState(hasMoreToLoad = true))

    private val timelineScope = CoroutineScope(SupervisorJob() + sessionDispatcher)
    private var observeJob: Job? = null
    private var sendingJob: Job? = null
    private var ignoredJob: Job? = null
    private var annotationsJob: Job? = null
    private var decryptedJob: Job? = null

    // Decryption writes the event table, which the timeline_event chunk flow doesn't observe, so a decrypt
    // completion won't re-map on its own. Coalesce a burst of decryptions (e.g. a key import) into one
    // cache-clearing rebuild that re-reads the fresh clear content.
    private val decryptedSignal = Channel<Unit>(Channel.CONFLATED)
    private val decryptedListener = TimelineEventDecryptor.OnEventDecryptedListener { decryptedSignal.trySend(Unit) }

    private var threadRootId: String? = null
    private val isThreadTimeline get() = threadRootId != null

    // The loaded chunk ids, newest-first; index 0 is the newest loaded chunk.
    private val loadedChunkIds = ArrayList<Long>()
    // Per-chunk mapped snapshots. Only the live (index-0) chunk changes on sync, so paginated history
    // chunks are mapped once and reused — the rebuild cost stays bounded as you scroll back.
    private val chunkSnapshotCache = HashMap<Long, List<TimelineEvent>>()
    @Volatile private var builtEvents: List<TimelineEvent> = emptyList()

    // Live timelines render a grow-only window (newest down to [oldestShownEventId]) instead of the whole
    // loaded chunk at once — building the whole chunk is catastrophic room-open on a single-core device.
    // Grow-only so there's no forward/backward oscillation. Off for thread timelines (small).
    private val windowGrowStep = 50
    private var pendingShowEventId: String? = initialEventId
    private var oldestShownEventId: String? = null
    @Volatile private var windowHasMoreOlder: Boolean = false
    private val isWindowed: Boolean get() = !isThreadTimeline
    private fun initialWindowCount() = settings.initialSize.coerceAtLeast(1)

    override val isLive: Boolean get() = !forwardState.get().hasMoreToLoad

    override fun addListener(listener: Timeline.Listener): Boolean {
        listeners.add(listener)
        timelineScope.launch {
            val snapshot = builtEvents
            withContext(coroutineDispatchers.main) { tryOrNull { listener.onTimelineUpdated(snapshot) } }
        }
        return true
    }

    override fun removeListener(listener: Timeline.Listener): Boolean = listeners.remove(listener)
    override fun removeAllListeners() = listeners.clear()

    override fun start(rootThreadEventId: String?) {
        if (!isStarted.compareAndSet(false, true)) return
        threadRootId = rootThreadEventId ?: settings.rootThreadEventId
        eventDecryptor.start()
        eventDecryptor.addOnDecryptedListener(decryptedListener)
        decryptedJob = timelineScope.launch {
            for (unused in decryptedSignal) {
                // Let a decrypt storm (e.g. opening an old room, or a key import) settle before re-mapping,
                // so the DB thread isn't starved rebuilding after every single event — they surface in batches.
                delay(DECRYPT_REBUILD_DEBOUNCE_MS)
                while (decryptedSignal.tryReceive().isSuccess) { /* drain the burst */ }
                chunkSnapshotCache.clear()
                rebuildSnapshot()
            }
        }
        timelineScope.launch {
            // A thread timeline gets a fresh (empty) thread chunk that the fetch task + sync then populate.
            val seed = if (isThreadTimeline) recreateThreadChunk(threadRootId!!) else resolveSeedChunkId()
            seedFrom(seed)
            rebuildSnapshot()
        }
    }

    override fun dispose() {
        isStarted.set(false)
        eventDecryptor.removeOnDecryptedListener(decryptedListener)
        eventDecryptor.destroy()
        observeJob?.cancel()
        sendingJob?.cancel()
        ignoredJob?.cancel()
        annotationsJob?.cancel()
        decryptedJob?.cancel()
        val rootId = threadRootId
        if (rootId != null) {
            // Drop the temporary thread chunk; keep the scope alive just long enough to commit it.
            timelineScope.launch {
                tryOrNull("SqlTimeline $roomId thread chunk cleanup failed") {
                    database.awaitDbTransaction(sessionDispatcher) { deleteThreadChunk(rootId) }
                }
            }
        } else {
            timelineScope.coroutineContext.cancelChildren()
        }
    }

    override fun restartWithEventId(eventId: String?) {
        timelineScope.launch {
            // Reset the window: null returns to the newest events; a target grows the window to include it.
            pendingShowEventId = eventId
            oldestShownEventId = null
            val seed = eventId?.let { chunkForEvent(it) } ?: resolveSeedChunkId()
            seedFrom(seed)
            rebuildSnapshot()
        }
    }

    override fun hasMoreToLoad(direction: Timeline.Direction): Boolean = getPaginationState(direction).hasMoreToLoad

    override fun paginate(direction: Timeline.Direction, count: Int) {
        timelineScope.launch { loadMore(count, direction) }
    }

    override suspend fun awaitPaginate(direction: Timeline.Direction, count: Int): List<TimelineEvent> {
        loadMore(count, direction)
        return builtEvents
    }

    override fun getIndexOfEvent(eventId: String?): Int? =
            eventId?.let { id -> builtEvents.indexOfFirst { it.eventId == id }.takeIf { it >= 0 } }

    override fun getPaginationState(direction: Timeline.Direction): Timeline.PaginationState =
            if (direction == Timeline.Direction.FORWARDS) forwardState.get() else backwardState.get()

    override fun getSnapshot(): List<TimelineEvent> = builtEvents

    private suspend fun resolveSeedChunkId(): Long? = when {
        threadRootId != null -> stores.chunk.lastForwardThread(roomId, threadRootId!!)?.id
        initialEventId != null -> chunkForEvent(initialEventId) ?: stores.chunk.lastForward(roomId)?.id
        else -> stores.chunk.lastForward(roomId)?.id
    }

    // The chunk holding [eventId], fetching its context from the server (which persists a chunk around it)
    // when it isn't loaded locally — otherwise jumping to a permalink / date result silently fell back to
    // the live edge, so navigation only worked for already-loaded events.
    private suspend fun chunkForEvent(eventId: String): Long? {
        stores.chunk.findChunkIdIncludingEvent(roomId, eventId)?.let { return it }
        tryOrNull("SqlTimeline $roomId context fetch for $eventId failed") {
            contextOfEventTask.execute(GetContextOfEventTask.Params(roomId, eventId))
        }
        return stores.chunk.findChunkIdIncludingEvent(roomId, eventId)
    }

    /** Clear any stale thread chunk and create a fresh empty one (forward thread chunk). */
    private suspend fun recreateThreadChunk(rootId: String): Long =
            database.awaitDbTransaction(sessionDispatcher) {
                deleteThreadChunk(rootId)
                stores.chunk.insert(roomId, null, null, null, null, isLastForward = false, isLastBackward = false, rootThreadEventId = rootId, isLastForwardThread = true)
            }

    private fun deleteThreadChunk(rootId: String) {
        stores.chunk.lastForwardThread(roomId, rootId)?.id?.let { chunkId ->
            stores.timelineEvent.deleteByChunk(chunkId)
            stores.chunk.deleteById(chunkId)
        }
    }

    private fun seedFrom(seedChunkId: Long?) {
        observeJob?.cancel()
        sendingJob?.cancel()
        ignoredJob?.cancel()
        annotationsJob?.cancel()
        loadedChunkIds.clear()
        chunkSnapshotCache.clear()
        if (seedChunkId == null) return
        loadedChunkIds.add(seedChunkId)
        // conflate: collapse a burst of row changes into one rebuild (each rebuild reads the latest state).
        observeJob = timelineScope.launch {
            snapshotLoader.chunkSnapshotFlow(seedChunkId).conflate().collect { rebuildSnapshot() }
        }
        sendingJob = timelineScope.launch {
            snapshotLoader.sendingEventsFlow(roomId).conflate().collect { rebuildSnapshot() }
        }
        // Re-filter the timeline instantly when the ignored-user set changes (ignore/unignore).
        ignoredJob = timelineScope.launch {
            snapshotLoader.ignoredUserIdsFlow().drop(1).collect { rebuildSnapshot() }
        }
        // Reactions/edits live in event_annotations_summary, which the chunk flow doesn't watch — rebuild
        // (clearing the static-chunk cache so history re-reads too) when a summary changes.
        annotationsJob = timelineScope.launch {
            snapshotLoader.annotationSummaryChangesFlow(roomId).drop(1).conflate().collect {
                chunkSnapshotCache.clear()
                rebuildSnapshot()
            }
        }
    }

    private suspend fun loadMore(count: Int, direction: Timeline.Direction) {
        if (isThreadTimeline) {
            loadMoreThread(count, direction)
            return
        }
        if (direction == Timeline.Direction.BACKWARDS) {
            if (isWindowed) {
                val all = computeLoadedEvents(reuseLiveChunk = true)
                val oldestIdx = oldestShownEventId?.let { id -> all.indexOfFirst { it.eventId == id } }?.takeIf { it >= 0 }
                        ?: (initialWindowCount() - 1).coerceAtMost(all.lastIndex)
                val target = oldestIdx + windowGrowStep
                // Loaded events still hidden above the window: reveal the next page without a fetch.
                if (target < all.size) {
                    oldestShownEventId = all[target].eventId
                    rebuildSnapshot(reuseLiveChunk = true)
                    return
                }
                // Window already reaches the oldest loaded event: reveal it, then fetch older below.
                oldestShownEventId = all.lastOrNull()?.eventId
            }
            val oldest = loadedChunkIds.lastOrNull()?.let { stores.chunk.getById(it) } ?: run {
                if (isWindowed) rebuildSnapshot(reuseLiveChunk = true)
                return
            }
            when {
                // is_last_backward is the room start: nothing older, whatever a stale prev link says.
                oldest.is_last_backward != 0L -> if (isWindowed) rebuildSnapshot(reuseLiveChunk = true) else updateState(Timeline.Direction.BACKWARDS) { it.copy(hasMoreToLoad = false) }
                oldest.prev_chunk_id != null -> {
                    extendLoadedChunks(Timeline.Direction.BACKWARDS)
                    revealAfterBackwardFetch()
                }
                oldest.prev_token != null -> {
                    paginate(oldest.prev_token, Timeline.Direction.BACKWARDS, count)
                    // The server page is persisted as a new chunk linked into our chain; walk the whole
                    // prev_chunk_id chain so a page that bridges to an existing older chunk is fully picked up.
                    extendLoadedChunks(Timeline.Direction.BACKWARDS)
                    revealAfterBackwardFetch()
                }
                else -> if (isWindowed) rebuildSnapshot(reuseLiveChunk = true) else updateState(Timeline.Direction.BACKWARDS) { it.copy(hasMoreToLoad = false) }
            }
        } else {
            val newest = loadedChunkIds.firstOrNull()?.let { stores.chunk.getById(it) } ?: return
            when {
                newest.is_last_forward != 0L -> updateState(Timeline.Direction.FORWARDS) { it.copy(hasMoreToLoad = false) }
                newest.next_chunk_id != null -> {
                    extendLoadedChunks(Timeline.Direction.FORWARDS)
                    rebuildSnapshot()
                }
                newest.next_token != null -> {
                    paginate(newest.next_token, Timeline.Direction.FORWARDS, count)
                    extendLoadedChunks(Timeline.Direction.FORWARDS)
                    rebuildSnapshot()
                }
                else -> updateState(Timeline.Direction.FORWARDS) { it.copy(hasMoreToLoad = false) }
            }
        }
    }

    /** Walk the prev_chunk_id (backwards) / next_chunk_id (forwards) links from the current edge, adding
     *  every transitively-linked chunk — so server pages that bridge or merge chunks are fully loaded. */
    private fun extendLoadedChunks(direction: Timeline.Direction) {
        if (direction == Timeline.Direction.BACKWARDS) {
            var tail = loadedChunkIds.lastOrNull()?.let { stores.chunk.getById(it) }
            while (true) {
                // The room start has nothing older: never follow a (possibly corrupt) prev link off an
                // is_last_backward chunk, or the walk wraps around into the live edge and pulls the whole
                // history in as "older than the first event".
                if (tail == null || tail.is_last_backward != 0L) break
                val prevId = tail.prev_chunk_id ?: break
                if (prevId in loadedChunkIds) break
                loadedChunkIds.add(prevId)
                tail = stores.chunk.getById(prevId)
            }
        } else {
            var head = loadedChunkIds.firstOrNull()?.let { stores.chunk.getById(it) }
            while (true) {
                // Symmetrically, the live edge has nothing newer.
                if (head == null || head.is_last_forward != 0L) break
                val nextId = head.next_chunk_id ?: break
                if (nextId in loadedChunkIds) break
                loadedChunkIds.add(0, nextId)
                head = stores.chunk.getById(nextId)
            }
        }
    }

    private suspend fun loadMoreThread(count: Int, direction: Timeline.Direction) {
        if (direction == Timeline.Direction.FORWARDS) {
            updateState(Timeline.Direction.FORWARDS) { it.copy(hasMoreToLoad = false) }
            return
        }
        val threadChunkId = loadedChunkIds.firstOrNull() ?: return
        val prevToken = stores.chunk.getById(threadChunkId)?.prev_token
        updateState(Timeline.Direction.BACKWARDS) { it.copy(loading = true) }
        val result = tryOrNull("SqlTimeline $roomId thread pagination failed") {
            fetchThreadTimelineTask.execute(FetchThreadTimelineTask.Params(roomId, threadRootId!!, prevToken, count))
        }
        val reachedEnd = result == DefaultFetchThreadTimelineTask.Result.REACHED_END
        updateState(Timeline.Direction.BACKWARDS) { it.copy(loading = false, hasMoreToLoad = !reachedEnd) }
        rebuildSnapshot()
    }

    private suspend fun paginate(token: String, direction: Timeline.Direction, count: Int) {
        updateState(direction) { it.copy(loading = true) }
        tryOrNull("SqlTimeline $roomId pagination failed") {
            paginationTask.execute(PaginationTask.Params(roomId, token, toPaginationDirection(direction), count))
        }
        updateState(direction) { it.copy(loading = false) }
    }

    private fun toPaginationDirection(direction: Timeline.Direction) =
            if (direction == Timeline.Direction.FORWARDS) PaginationDirection.FORWARDS else PaginationDirection.BACKWARDS

    private fun computeLoadedEvents(reuseLiveChunk: Boolean = false): List<TimelineEvent> {
        val liveChunkId = loadedChunkIds.firstOrNull()
        val chunkEvents = loadedChunkIds.flatMap { chunkId ->
            if (chunkId == liveChunkId && !reuseLiveChunk) {
                // the live/changing chunk is re-mapped on sync/content changes...
                snapshotLoader.chunkSnapshot(chunkId).also { chunkSnapshotCache[chunkId] = it }
            } else {
                // ...but static history chunks — and the live chunk during a pure backward-scroll reveal
                // (reuseLiveChunk), which never changes its content — reuse the cached mapping. Re-resolving
                // a large live chunk on every scroll page was a scroll-lag source.
                chunkSnapshotCache.getOrPut(chunkId) { snapshotLoader.chunkSnapshot(chunkId) }
            }
        }
        val sending = when {
            // Only the local echoes posted into this thread belong at its live edge.
            isThreadTimeline -> snapshotLoader.sendingEvents(roomId).filter { it.root.getRootThreadEventId() == threadRootId }
            isLiveEdgeLoaded() -> snapshotLoader.sendingEvents(roomId)
            else -> emptyList()
        }
        // Hide ignored users' messages at display time (keep their state events, per the spec). Filtering
        // here — rather than deleting rows — is what makes unignore instant: nothing was ever removed.
        val ignored = stores.user.getIgnoredUserIds().toSet()
        return (sending + chunkEvents).filterNot { it.root.senderId in ignored && it.root.stateKey == null }
    }

    // Index 0 is newest (display_index DESC). Keep newest down to [oldestShownEventId], growing the anchor
    // to include a pending navigation target. Grow-only — never trims.
    private fun applyWindow(all: List<TimelineEvent>): List<TimelineEvent> {
        if (!isWindowed || all.isEmpty()) return all
        pendingShowEventId?.let { id ->
            val idx = all.indexOfFirst { it.eventId == id }
            if (idx >= 0) {
                pendingShowEventId = null
                val want = (idx + initialWindowCount()).coerceAtMost(all.lastIndex)
                val current = oldestShownEventId?.let { e -> all.indexOfFirst { it.eventId == e } } ?: -1
                if (want > current) oldestShownEventId = all[want].eventId
            }
        }
        val oldestIdx = (oldestShownEventId?.let { id -> all.indexOfFirst { it.eventId == id } }
                ?.takeIf { it >= 0 } ?: (initialWindowCount() - 1)).coerceIn(0, all.lastIndex)
        oldestShownEventId = all[oldestIdx].eventId
        return all.take(oldestIdx + 1)
    }

    // After loading older events from disk/server, advance the window a page older to reveal them.
    private suspend fun revealAfterBackwardFetch() {
        if (isWindowed) {
            val all = computeLoadedEvents(reuseLiveChunk = true)
            val oldestIdx = oldestShownEventId?.let { id -> all.indexOfFirst { it.eventId == id } }?.takeIf { it >= 0 } ?: all.lastIndex
            oldestShownEventId = all.getOrNull((oldestIdx + windowGrowStep).coerceAtMost(all.lastIndex))?.eventId
        }
        rebuildSnapshot(reuseLiveChunk = true)
    }

    private suspend fun rebuildSnapshot(reuseLiveChunk: Boolean = false) {
        val all = computeLoadedEvents(reuseLiveChunk)
        val events = applyWindow(all)
        builtEvents = events
        requestDecryptionForUtd(events)
        windowHasMoreOlder = isWindowed && events.isNotEmpty() && events.last().eventId != all.last().eventId
        refreshPaginationStates()
        Timber.v("SqlTimeline $roomId rebuilt snapshot of ${events.size}/${all.size} events")
        withContext(coroutineDispatchers.main) {
            listeners.forEach { tryOrNull { it.onTimelineUpdated(events) } }
        }
    }

    // Persisted UTD events are only decrypted at sync/insert time (skipped on initial sync) and, for the
    // room's latest previewable event, by the room-summary decryptor — so on opening an old room every
    // other encrypted event stays UTD until we ask here. requestDecryption dedupes in-flight/failed ones.
    private fun requestDecryptionForUtd(events: List<TimelineEvent>) {
        val requests = events.mapNotNull { event ->
            event.root.takeIf { it.isEncrypted() && it.mxDecryptionResult == null }
                    ?.let { TimelineEventDecryptor.DecryptionRequest(it, timelineID) }
        }
        if (requests.isNotEmpty()) eventDecryptor.requestDecryption(requests)
    }

    private fun isLiveEdgeLoaded(): Boolean =
            loadedChunkIds.firstOrNull()?.let { stores.chunk.getById(it) }?.is_last_forward == 1L

    private fun refreshPaginationStates() {
        // Thread pagination state is driven directly by fetchThreadTimelineTask results in loadMoreThread.
        if (isThreadTimeline) return
        val oldest = loadedChunkIds.lastOrNull()?.let { stores.chunk.getById(it) }
        val moreBackward = windowHasMoreOlder ||
                (oldest != null && oldest.is_last_backward == 0L && (oldest.prev_chunk_id != null || oldest.prev_token != null))
        updateState(Timeline.Direction.BACKWARDS) { it.copy(hasMoreToLoad = moreBackward) }

        val newest = loadedChunkIds.firstOrNull()?.let { stores.chunk.getById(it) }
        val moreForward = newest != null && newest.is_last_forward == 0L &&
                (newest.next_chunk_id != null || newest.next_token != null)
        updateState(Timeline.Direction.FORWARDS) { it.copy(hasMoreToLoad = moreForward) }
    }

    private fun updateState(direction: Timeline.Direction, update: (Timeline.PaginationState) -> Timeline.PaginationState) {
        val stateRef = if (direction == Timeline.Direction.FORWARDS) forwardState else backwardState
        val current = stateRef.get()
        val newValue = update(current)
        if (newValue == current) return
        stateRef.set(newValue)
        // Listener callbacks must land on the main thread (consistent with onTimelineUpdated above).
        timelineScope.launch(coroutineDispatchers.main) {
            listeners.forEach { tryOrNull { it.onStateUpdated(direction, newValue) } }
        }
    }

    companion object {
        private const val DECRYPT_REBUILD_DEBOUNCE_MS = 150L
    }
}
