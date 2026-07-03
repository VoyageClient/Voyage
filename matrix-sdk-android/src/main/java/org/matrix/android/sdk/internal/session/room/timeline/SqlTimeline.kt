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
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.getRootThreadEventId
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.util.MatrixPerf
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.TimelineSettings
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.session.room.relation.threads.DefaultFetchThreadTimelineTask
import org.matrix.android.sdk.internal.session.room.relation.threads.FetchThreadTimelineTask
import org.matrix.android.sdk.internal.util.time.Clock
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
        private val timelineInput: TimelineInput,
        private val clock: Clock,
) : Timeline, TimelineInput.Listener, UIEchoManager.Listener {

    override val timelineID = UUID.randomUUID().toString()

    private val listeners = CopyOnWriteArrayList<Timeline.Listener>()
    private val isStarted = AtomicBoolean(false)
    private val forwardState = AtomicReference(Timeline.PaginationState(hasMoreToLoad = false))
    private val backwardState = AtomicReference(Timeline.PaginationState(hasMoreToLoad = true))

    private val timelineScope = CoroutineScope(SupervisorJob() + sessionDispatcher)
    // The backward loading item re-fires onLoadMore every time it's visible; in a room dominated by collapsed
    // (hidden/redacted) events it stays on screen, so serialize the requests to avoid piling up fetches.
    private val backwardPaginating = java.util.concurrent.atomic.AtomicBoolean(false)
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

    // In-memory echo of just-sent events and their send-state transitions: the DB round-trip (insert on
    // the session dispatcher, flow emission, snapshot rebuild) is far too slow for perceived send latency,
    // and send-state updates only touch the event table, which the timeline_event flows don't observe.
    private val uiEchoManager = UIEchoManager(this, clock)

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

    // While the user sits at the live edge, every synced message widens the (grow-only) window —
    // rebuilds, model passes and diffs get slower the longer a room stays open. Cap it there:
    // each new message nudges the oldest shown event out instead of growing the span. Nothing is
    // unloaded — hidden events re-reveal instantly through the normal backward reveal on scroll-up.
    private val windowLiveEdgeCap = 120
    // Seeded false for a permalink open so the cap can't clip the window above the target event
    // before the first scroll callback arrives.
    @Volatile private var viewAtLiveEdge: Boolean = initialEventId == null

    // The live chunk can hold thousands of rows in a redaction-heavy or busy room. Mapping all of them on
    // every rebuild — room-open included — is the "slow to open every time" cost, even though the window only
    // ever shows the newest slice. Map only the newest [liveChunkRowCap] rows and grow the slice in
    // [liveChunkRowStep] increments as the user reveals older content (see growLiveChunkMapping).
    private val liveChunkRowStep = 400
    @Volatile private var liveChunkRowCap = liveChunkRowStep
    // Max history chunks pulled into the loaded set per backward pagination (each is mapped on the next
    // rebuild, so this bounds per-pass mapping cost). The reveal loop keeps calling back to walk older.
    private val maxBackwardChunks = 3
    // True once the mapped slice covers the whole live chunk; while false there are older rows we haven't
    // mapped yet, so the timeline must still offer a backward-reveal affordance.
    @Volatile private var liveChunkFullyMapped = false

    private var pendingShowEventId: String? = initialEventId
    private var oldestShownEventId: String? = null
    @Volatile private var windowHasMoreOlder: Boolean = false
    // Cached so the instant-echo path (main thread) doesn't need a DB read.
    @Volatile private var liveEdgeLoaded: Boolean = false
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
        timelineInput.listeners.add(this)
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
        timelineInput.listeners.remove(this)
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

    override fun setViewAtLiveEdge(atLiveEdge: Boolean) {
        viewAtLiveEdge = atLiveEdge
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
        liveChunkRowCap = liveChunkRowStep
        liveChunkFullyMapped = false
        if (seedChunkId == null) return
        loadedChunkIds.add(seedChunkId)
        // conflate: collapse a burst of row changes into one rebuild (each rebuild reads the latest state).
        observeJob = timelineScope.launch {
            snapshotLoader.chunkChangesFlow(seedChunkId).conflate().collect { rebuildSnapshot() }
        }
        // Local-echo add/remove and ignore-set changes don't alter the live chunk's rows, so those
        // rebuilds reuse its cached mapping instead of re-resolving the whole chunk.
        sendingJob = timelineScope.launch {
            snapshotLoader.sendingChangesFlow(roomId).conflate().collect { rebuildSnapshot(reuseLiveChunk = true) }
        }
        ignoredJob = timelineScope.launch {
            snapshotLoader.ignoredUserIdsFlow().drop(1).collect { rebuildSnapshot(reuseLiveChunk = true) }
        }
        // Reactions/edits live in event_annotations_summary, which the chunk flow doesn't watch — rebuild
        // (clearing the static-chunk cache so history re-reads too) when a summary changes.
        annotationsJob = timelineScope.launch {
            snapshotLoader.annotationSummaryChangesFlow(roomId).drop(1).conflate().collect {
                // Marker: reaction/edit propagation into the visible timeline (must fire and stay
                // fast regardless of scroll position or the live-edge window cap).
                val perfStart = MatrixPerf.now()
                chunkSnapshotCache.clear()
                rebuildSnapshot()
                MatrixPerf.end(perfStart) { "timeline.annotationsPropagate" }
            }
        }
    }

    private suspend fun loadMore(count: Int, direction: Timeline.Direction) {
        if (isThreadTimeline) {
            loadMoreThread(count, direction)
            return
        }
        if (direction == Timeline.Direction.BACKWARDS) {
            // Ignore overlapping requests; the visible loading item fires this continuously in a
            // redaction-heavy room.
            if (!backwardPaginating.compareAndSet(false, true)) return
            try {
                if (isWindowed) {
                    val all = computeLoadedEvents(reuseLiveChunk = true)
                    val oldestIdx = oldestShownEventId?.let { id -> all.indexOfFirst { it.eventId == id } }?.takeIf { it >= 0 }
                            ?: (initialWindowCount() - 1).coerceAtMost(all.lastIndex)
                    val target = advanceByMessages(all, oldestIdx, windowGrowStep)
                    // Reached windowGrowStep more messages while still inside the loaded set: reveal, no fetch.
                    if (target < all.lastIndex) {
                        oldestShownEventId = all[target].eventId
                        rebuildSnapshot(reuseLiveChunk = true)
                        return
                    }
                    // Window already reaches the oldest loaded event: reveal it, then fetch older below.
                    oldestShownEventId = all.lastOrNull()?.eventId
                    // If the live chunk still has older rows we've not mapped yet, widen the mapped slice and
                    // reveal within it instead of paginating older chunks / the server.
                    if (growLiveChunkMapping()) {
                        revealAfterBackwardFetch()
                        return
                    }
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
            } finally {
                backwardPaginating.set(false)
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
     *  transitively-linked chunks — so server pages that bridge or merge chunks are picked up. Backwards is
     *  capped at [maxBackwardChunks] per call: each newly-loaded chunk is mapped in full on the next rebuild,
     *  so pulling the whole chain at once (dozens of chunks in a redaction-heavy room) froze the open for
     *  seconds. Adding a few at a time spreads that mapping across passes; the reveal loop re-invokes us to
     *  keep walking older. */
    private fun extendLoadedChunks(direction: Timeline.Direction) {
        if (direction == Timeline.Direction.BACKWARDS) {
            var tail = loadedChunkIds.lastOrNull()?.let { stores.chunk.getById(it) }
            var added = 0
            while (added < maxBackwardChunks) {
                // The room start has nothing older: never follow a (possibly corrupt) prev link off an
                // is_last_backward chunk, or the walk wraps around into the live edge and pulls the whole
                // history in as "older than the first event".
                if (tail == null || tail.is_last_backward != 0L) break
                val prevId = tail.prev_chunk_id ?: break
                if (prevId in loadedChunkIds) break
                loadedChunkIds.add(prevId)
                added++
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
            if (chunkId == liveChunkId) {
                // the live/changing chunk is refreshed on sync/content changes (incrementally when possible);
                // during a pure backward-scroll reveal (reuseLiveChunk) its content is unchanged, so reuse the
                // cached bounded slice. Either way the mapping is bounded to the newest [liveChunkRowCap] rows.
                if (reuseLiveChunk) chunkSnapshotCache[chunkId] ?: loadLiveChunkNewest(chunkId).also { chunkSnapshotCache[chunkId] = it }
                else refreshLiveChunkSnapshot(chunkId)
            } else {
                // Static history chunks (bounded pagination pages) are mapped once and reused.
                chunkSnapshotCache.getOrPut(chunkId) { snapshotLoader.chunkSnapshot(chunkId) }
            }
        }
        // A fast remote echo can arrive before any rebuild saw the DB sending row (the conflated flow
        // collapses insert+delete), stranding the in-memory copy — reconcile against synced transaction ids.
        if (uiEchoManager.getInMemorySendingEvents().isNotEmpty()) {
            chunkEvents.forEach { event ->
                event.root.unsignedData?.transactionId?.let { uiEchoManager.onSyncedEvent(it) }
            }
        }
        val sending = if (isThreadTimeline || isLiveEdgeLoaded()) {
            val dbSending = snapshotLoader.sendingEvents(roomId)
            uiEchoManager.onSentEventsInDatabase(dbSending.map { it.eventId })
            (uiEchoManager.getInMemorySendingEvents() + dbSending)
                    .distinctBy { it.eventId }
                    // Only the local echoes posted into this thread belong at its live edge.
                    .let { if (isThreadTimeline) it.filter { e -> e.root.getRootThreadEventId() == threadRootId } else it }
                    .map { uiEchoManager.updateSentStateWithUiEcho(it) }
        } else {
            emptyList()
        }
        // Hide ignored users' messages at display time (keep their state events, per the spec). Filtering
        // here — rather than deleting rows — is what makes unignore instant: nothing was ever removed.
        val ignored = stores.user.getIgnoredUserIds().toSet()
        return (sending + chunkEvents)
                .filterNot { it.root.senderId in ignored && it.root.stateKey == null }
                .map { uiEchoManager.decorateEventWithReactionUiEcho(it) }
    }

    // Index 0 is newest (display_index DESC). Keep newest down to [oldestShownEventId], growing the anchor
    // to include a pending navigation target. Grows on reveal; only capped at the live edge (see
    // windowLiveEdgeCap) so nothing moves under a scrolled-up reader.
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
        val anchorIdx = oldestShownEventId?.let { id -> all.indexOfFirst { it.eventId == id } }
        var oldestIdx = (anchorIdx?.takeIf { it >= 0 } ?: (initialWindowCount() - 1)).coerceIn(0, all.lastIndex)
        // Cap the live-edge window by the count of *message* events, not raw events: a flood of redactions
        // or state changes (e.g. a mass redaction) collapses to a single merged item, so a raw cap would
        // show that one block and nothing else — no content, no scroll affordance to grow the window.
        val capIdx = if (viewAtLiveEdge && pendingShowEventId == null) contentWindowCapIndex(all) else all.lastIndex
        if (oldestIdx > capIdx) {
            oldestIdx = capIdx
        }
        oldestShownEventId = all[oldestIdx].eventId
        return all.take(oldestIdx + 1)
    }

    // Index of the [windowLiveEdgeCap]-th message event from the live edge (or the last loaded index if
    // there are fewer). Non-message events (redactions, reactions, state) between messages ride along for
    // free, so a burst of them can't crowd real content out of the live-edge window.
    private fun contentWindowCapIndex(all: List<TimelineEvent>): Int {
        var messages = 0
        for (i in all.indices) {
            if (all[i].isMessageContent()) {
                messages++
                if (messages >= windowLiveEdgeCap) return i
            }
        }
        return all.lastIndex
    }

    private fun TimelineEvent.isMessageContent(): Boolean = when (root.getClearType()) {
        EventType.MESSAGE, EventType.ENCRYPTED, EventType.STICKER -> true
        else -> false
    }

    // Index reached by revealing [messageStep] more message events past [fromIdx], skipping interleaved
    // hidden/redaction/state events. Growing the window by *raw* count instead crawled through a big hidden
    // run 50 events at a time — and since the run collapses to one item that never fills the screen, the
    // backward-reveal kept re-triggering, walking the whole timeline. Jumping by message count reveals real
    // content in one step so the loop terminates.
    private fun advanceByMessages(all: List<TimelineEvent>, fromIdx: Int, messageStep: Int): Int {
        var messages = 0
        var i = fromIdx + 1
        while (i <= all.lastIndex) {
            if (all[i].isMessageContent() && ++messages >= messageStep) return i
            i++
        }
        return all.lastIndex
    }

    // After loading older events from disk/server, advance the window a page older to reveal them.
    private suspend fun revealAfterBackwardFetch() {
        if (isWindowed) {
            val all = computeLoadedEvents(reuseLiveChunk = true)
            val oldestIdx = oldestShownEventId?.let { id -> all.indexOfFirst { it.eventId == id } }?.takeIf { it >= 0 } ?: all.lastIndex
            oldestShownEventId = all.getOrNull(advanceByMessages(all, oldestIdx, windowGrowStep))?.eventId
        }
        rebuildSnapshot(reuseLiveChunk = true)
    }

    private suspend fun rebuildSnapshot(reuseLiveChunk: Boolean = false) {
        val perfStart = MatrixPerf.now()
        liveEdgeLoaded = isLiveEdgeLoaded()
        val all = computeLoadedEvents(reuseLiveChunk)
        MatrixPerf.end(perfStart) { "timeline.computeLoadedEvents reuse=$reuseLiveChunk chunks=${loadedChunkIds.size} events=${all.size}" }
        val events = applyWindow(all)
        // The memoized mappers return the same instances for unchanged events, so a reference sweep
        // detects a no-op rebuild. Skipping the notify matters: each snapshot posted wakes the epoxy
        // controller for a full model pass (~0.5s on device), and redundant posts were queueing behind
        // each other and delaying real updates (like a just-sent message) by seconds.
        val unchanged = sameByReference(events, builtEvents)
        builtEvents = events
        requestDecryptionForUtd(events)
        windowHasMoreOlder = isWindowed && events.isNotEmpty() &&
                (events.last().eventId != all.last().eventId || !liveChunkFullyMapped)
        refreshPaginationStates()
        Timber.v("SqlTimeline $roomId rebuilt snapshot of ${events.size}/${all.size} events (unchanged=$unchanged)")
        MatrixPerf.end(perfStart) { "timeline.rebuildSnapshot reuse=$reuseLiveChunk shown=${events.size}/${all.size} unchanged=$unchanged" }
        if (!unchanged) {
            withContext(coroutineDispatchers.main) {
                listeners.forEach { tryOrNull { it.onTimelineUpdated(events) } }
            }
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

    /**
     * The live chunk grows with every sync, and re-loading + re-mapping it in full made each incoming
     * message cost O(chunk size) — the "app gets slower the longer it runs" mechanism. Its rows are
     * append-only in practice, so load only the rows above the cached max display index and prepend.
     * Fall back to a full reload when the count doesn't add up (rows were removed / chunk rebuilt) or a
     * new event is a redaction (it prunes an OLDER root in place, which the cached mapping would miss).
     * In-place edits/reactions/decryptions are covered by the annotation/decrypt jobs, which clear the
     * cache entirely before rebuilding.
     */
    private fun refreshLiveChunkSnapshot(chunkId: Long): List<TimelineEvent> {
        val cached = chunkSnapshotCache[chunkId]
        if (cached.isNullOrEmpty()) {
            return loadLiveChunkNewest(chunkId).also { chunkSnapshotCache[chunkId] = it }
        }
        val newEvents = snapshotLoader.chunkSnapshotAfter(chunkId, cached.first().displayIndex.toLong())
        // A redaction prunes an OLDER root in place (its display_index is unchanged, so the append query above
        // never re-maps it) — reload the bounded slice so the redacted content drops out. The redaction event
        // lands in the live chunk, but its target can sit in any loaded chunk, so also drop the cached history
        // mappings; they re-map with the pruned content on this same rebuild (the live chunk is index 0, so
        // this runs before the flatMap reaches them). During a mass redaction history usually isn't loaded, so
        // there is normally nothing to drop.
        if (newEvents.any { it.root.getClearType() == EventType.REDACTION }) {
            loadedChunkIds.forEach { if (it != chunkId) chunkSnapshotCache.remove(it) }
            return loadLiveChunkNewest(chunkId).also { chunkSnapshotCache[chunkId] = it }
        }
        if (newEvents.isEmpty()) return cached
        return (newEvents + cached).also { chunkSnapshotCache[chunkId] = it }
    }

    // Map only the newest [liveChunkRowCap] rows of the live chunk (unless a permalink target is pending —
    // that event may sit deep in the chunk, so map it whole to be sure it's reachable).
    private fun loadLiveChunkNewest(chunkId: Long): List<TimelineEvent> {
        // Threads aren't windowed (they page from the server), and a permalink target may sit deep in the
        // chunk — map the whole chunk in both cases so nothing is unreachable.
        if (isThreadTimeline || pendingShowEventId != null) {
            liveChunkFullyMapped = true
            return snapshotLoader.chunkSnapshot(chunkId)
        }
        val slice = snapshotLoader.chunkSnapshotNewest(chunkId, liveChunkRowCap.toLong())
        liveChunkFullyMapped = slice.size >= snapshotLoader.chunkEventCount(chunkId).toInt()
        return slice
    }

    // When a backward reveal reaches the oldest mapped row but the live chunk still has older rows we haven't
    // mapped, widen the mapped slice instead of paginating older chunks. Appends only the next step of older
    // rows to the cached slice (O(step)) rather than re-mapping the whole, growing slice. Returns true if it grew.
    private fun growLiveChunkMapping(): Boolean {
        if (liveChunkFullyMapped) return false
        val liveChunkId = loadedChunkIds.firstOrNull() ?: return false
        val cached = chunkSnapshotCache[liveChunkId]
        if (cached.isNullOrEmpty()) {
            liveChunkRowCap += liveChunkRowStep
            return true
        }
        val older = snapshotLoader.chunkSnapshotOlderThan(liveChunkId, cached.last().displayIndex.toLong(), liveChunkRowStep.toLong())
        liveChunkRowCap += liveChunkRowStep
        if (older.isEmpty()) {
            liveChunkFullyMapped = true
            return false
        }
        chunkSnapshotCache[liveChunkId] = cached + older
        liveChunkFullyMapped = older.size < liveChunkRowStep
        return true
    }

    private fun sameByReference(a: List<TimelineEvent>, b: List<TimelineEvent>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (a[i] !== b[i]) return false
        }
        return true
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

    // TimelineInput callbacks: the send pipeline's in-memory signal, so a sent message and its
    // send-state transitions show instantly instead of waiting for the DB flow (echo insert) or a sync
    // round-trip (send-state lives in the event table, which the timeline_event flows don't observe).
    // These run on the MAIN dispatcher, not the session dispatcher: the DB thread can be hundreds of ms
    // behind (sync handling, chunk mapping) and the whole point is showing the echo instantly. A
    // concurrent DB-thread rebuild can overwrite the optimistic prepend, but it merges the same echo
    // back in from uiEchoManager's in-memory list, so the loss is at most one frame.
    override fun onLocalEchoCreated(roomId: String, timelineEvent: TimelineEvent) {
        if (roomId != this.roomId || !isStarted.get()) return
        timelineScope.launch(coroutineDispatchers.main) {
            if (isThreadTimeline && timelineEvent.root.getRootThreadEventId() != threadRootId) return@launch
            if (!isThreadTimeline && !liveEdgeLoaded) return@launch
            uiEchoManager.onLocalEchoCreated(timelineEvent)
            // A DB-flow rebuild may already have picked the echo up from the sending table.
            if (builtEvents.none { it.eventId == timelineEvent.eventId }) {
                builtEvents = listOf(timelineEvent) + builtEvents
                listeners.forEach { tryOrNull { it.onTimelineUpdated(builtEvents) } }
            }
        }
    }

    override fun onLocalEchoUpdated(roomId: String, eventId: String, sendState: SendState) {
        if (roomId != this.roomId || !isStarted.get()) return
        timelineScope.launch(coroutineDispatchers.main) {
            if (!uiEchoManager.onSendStateUpdated(eventId, sendState)) return@launch
            val current = builtEvents
            val idx = current.indexOfFirst { it.eventId == eventId }
            if (idx < 0) return@launch
            builtEvents = current.toMutableList().also { it[idx] = uiEchoManager.updateSentStateWithUiEcho(current[idx]) }
            listeners.forEach { tryOrNull { it.onTimelineUpdated(builtEvents) } }
        }
    }

    // A limited (gappy) sync clears the room's chunks and starts a fresh last-forward chunk (see
    // SqlRoomSyncHandler.handleTimelineEvents). Our observe job is bound to the old — now deleted — chunk,
    // so the synced events never surface until the room is reopened. Detect the live chunk moving and
    // re-seed onto it, back at the live edge.
    override fun onNewTimelineEvents(roomId: String, eventIds: List<String>) {
        if (roomId != this.roomId || !isStarted.get() || isThreadTimeline) return
        timelineScope.launch {
            val currentLive = loadedChunkIds.firstOrNull() ?: return@launch
            val liveChunkId = stores.chunk.lastForward(this@SqlTimeline.roomId)?.id ?: return@launch
            if (liveChunkId == currentLive) return@launch
            pendingShowEventId = null
            oldestShownEventId = null
            seedFrom(liveChunkId)
            rebuildSnapshot()
        }
    }

    /** [UIEchoManager.Listener]: patch one event in the built snapshot (reaction ui-echo decoration). */
    override fun rebuildEvent(eventId: String, builder: (TimelineEvent) -> TimelineEvent?): Boolean {
        val current = builtEvents
        val idx = current.indexOfFirst { it.eventId == eventId }
        if (idx < 0) return false
        val updated = builder(current[idx]) ?: return false
        builtEvents = current.toMutableList().also { it[idx] = updated }
        timelineScope.launch { notifySnapshot() }
        return true
    }

    private suspend fun notifySnapshot() {
        val snapshot = builtEvents
        withContext(coroutineDispatchers.main) {
            listeners.forEach { tryOrNull { it.onTimelineUpdated(snapshot) } }
        }
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
