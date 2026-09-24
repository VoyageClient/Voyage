/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.getRootThreadEventId
import org.matrix.android.sdk.api.session.profile.ProfileOverrides
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.TimelineSettings
import org.matrix.android.sdk.api.util.MatrixPerf
import org.matrix.android.sdk.api.util.RoomOpenTrace
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.session.room.membership.LoadRoomMembersTask
import org.matrix.android.sdk.internal.session.room.relation.threads.DefaultFetchThreadTimelineTask
import org.matrix.android.sdk.internal.session.room.relation.threads.FetchThreadTimelineTask
import org.matrix.android.sdk.internal.session.sync.sliding.SlidingSyncRoomSubscriptions
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A timeline binds to one timestamp-ordered range and grows it through pagination and merging.
 * Local echoes appear above it when the live edge is loaded.
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
        private val fetchRoomStartTask: FetchRoomStartTask,
        private val database: SessionSqlDatabase,
        private val sessionDispatcher: CoroutineDispatcher,
        private val readDispatcher: CoroutineDispatcher,
        private val eventDecryptor: TimelineEventDecryptor,
        private val timelineInput: TimelineInput,
        private val clock: Clock,
        private val redactionSignal: TimelineRedactionSignal,
        private val decryptionSignal: TimelineDecryptionSignal,
        private val loadRoomMembersTask: LoadRoomMembersTask,
        private val gapHealer: TimelineGapHealer,
        private val slidingSyncRoomSubscriptions: SlidingSyncRoomSubscriptions,
) : Timeline, TimelineInput.Listener, UIEchoManager.Listener {

    override val timelineID = UUID.randomUUID().toString()

    private val listeners = CopyOnWriteArrayList<Timeline.Listener>()
    private val isStarted = AtomicBoolean(false)
    private val hasRoomSubscription = AtomicBoolean(false)
    private val forwardState = AtomicReference(Timeline.PaginationState(hasMoreToLoad = false))
    private val backwardState = AtomicReference(Timeline.PaginationState(hasMoreToLoad = true))

    // Reads only, and never on the write thread: sharing it meant a room open blocked until sync's one big
    // transaction committed. Writes still hop to [sessionDispatcher] — opening a transaction here would only
    // move the stall to SQLite's writer lock. Must stay single-threaded; the window bookkeeping below relies
    // on confinement rather than locking.
    private val timelineScope = CoroutineScope(SupervisorJob() + readDispatcher)

    // The backward loading item re-fires onLoadMore every time it's visible; in a room dominated by collapsed
    // (hidden/redacted) events it stays on screen, so serialize the requests to avoid piling up fetches.
    private val backwardPaginating = java.util.concurrent.atomic.AtomicBoolean(false)
    private val forwardPaginating = java.util.concurrent.atomic.AtomicBoolean(false)
    private val healsInFlight = java.util.Collections.synchronizedSet(HashSet<String>())
    private var observeJob: Job? = null
    private var sendingJob: Job? = null
    private var ignoredJob: Job? = null
    private var annotationsJob: Job? = null
    private var receiptsJob: Job? = null
    private var decryptedJob: Job? = null
    private var decryptionSignalJob: Job? = null
    private var profileOverridesJob: Job? = null

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

    private var seenRedactionStamp = redactionSignal.stamp(roomId)

    private fun consumeRedactionStamp(): Boolean {
        val stamp = redactionSignal.stamp(roomId)
        if (stamp == seenRedactionStamp) return false
        seenRedactionStamp = stamp
        return true
    }

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

    // True once the mapped slice covers the whole live chunk; while false there are older rows we haven't
    // mapped yet, so the timeline must still offer a backward-reveal affordance.
    @Volatile private var liveChunkFullyMapped = false

    private var pendingShowEventId: String? = initialEventId
    private var oldestShownEventId: String? = null

    // Newer bound of the window, set when jumping deep into history: without it the window spans
    // live edge → target, which in a busy room is thousands of events to map and model-build at
    // once. null = the window reaches the newest loaded event (the normal live case). Newer events
    // are revealed step-wise through forward pagination, mirroring the backward reveal.
    // Volatile: also read on the main thread by the local-echo fast path.
    @Volatile private var newestShownEventId: String? = null
    @Volatile private var windowHasMoreOlder: Boolean = false
    @Volatile private var windowHasMoreNewer: Boolean = false

    // Cached so the instant-echo path (main thread) doesn't need a DB read.
    @Volatile private var liveEdgeLoaded: Boolean = false
    private val isWindowed: Boolean get() = !isThreadTimeline
    private fun initialWindowCount() = settings.initialSize.coerceAtLeast(1)

    // A context range without a forward token may still be far from the live edge.
    override val isLive: Boolean get() = liveEdgeLoaded && !forwardState.get().hasMoreToLoad

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
        updateRoomSubscription(true)
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
        // A decrypt done by another component (the room-summary decryptor decrypts this room's latest
        // event) writes to the event table without hitting our decryptor's listener — fold it into the
        // same debounced refresh so the preview event stops rendering as encrypted.
        decryptionSignalJob = timelineScope.launch {
            decryptionSignal.rooms.collect { if (it == roomId) decryptedSignal.trySend(Unit) }
        }
        profileOverridesJob = timelineScope.launch {
            ProfileOverrides.changes.collect { changedUsers ->
                // Only the affected events: dropping the cache re-maps every loaded row, which in a
                // scrolled-back room takes seconds — long enough that the override lands well after the
                // user is back in the timeline.
                remapCachedEventsOfUsers(changedUsers)
                // Force: overrides are set from the member-profile screen, which pauses this timeline, and
                // a deferred rebuild leaves builtEvents mapped under the old overrides for addListener to
                // re-publish on the way back.
                rebuildSnapshot(force = true)
            }
        }
        timelineScope.launch {
            delay(ROOM_MEMBER_LOAD_DELAY_MS)
            RoomOpenTrace.stageFor(roomId, "sdk.loadRoomMembers.start")
            loadRoomMembers()
            RoomOpenTrace.stageFor(roomId, "sdk.loadRoomMembers.done")
        }
        timelineScope.launch {
            RoomOpenTrace.stageFor(roomId, "sdk.seedJob.enter")
            if (!isThreadTimeline) {
                // Deliberately NOT on sessionDispatcher: that queue is serialized behind sync's multi-second
                // write transactions, and this read gates the seed — i.e. the whole room open.
                val membership = stores.room.get(roomId)?.membership
                RoomOpenTrace.stageFor(roomId, "sdk.membershipRead", "membership=$membership")
                if (membership == Membership.LEAVE || membership == Membership.BAN) {
                    // A boundary marked by an earlier 403 isn't authoritative — the server's
                    // departed-access policy varies per room — so a removed room re-probes once per
                    // open; a genuine room start just re-marks.
                    database.awaitDbTransaction(sessionDispatcher) { stores.chunk.clearLastBackward(roomId) }
                }
            }
            // A thread timeline gets a fresh (empty) thread chunk that the fetch task + sync then populate.
            val seed = if (isThreadTimeline) recreateThreadChunk(threadRootId!!) else resolveSeedChunkId()
            RoomOpenTrace.stageFor(roomId, "sdk.seedResolved", "chunk=$seed")
            seedFrom(seed)
            RoomOpenTrace.stageFor(roomId, "sdk.seeded")
            rebuildSnapshot()
            RoomOpenTrace.stageFor(roomId, "sdk.firstRebuild", "built=${builtEvents.size}")
            // The UI only asks for older events once its loading item is on screen, which waits for the
            // first models to build — seconds in a room whose cache holds little. Fetch that page here
            // instead. Unconditionally: this runs only when the seed range is short of a screenful, and a
            // limited sync leaves exactly that — a new live range holding a handful of events, with the
            // room's stored history behind the gap it opened. Waiting for a sliding-sync subscription to
            // fill it costs the same round trip and shows one message meanwhile.
            if (!isThreadTimeline && initialEventId == null && builtEvents.size < initialWindowCount()) {
                RoomOpenTrace.stageFor(roomId, "sdk.initialLoadMore.start", "built=${builtEvents.size}/${initialWindowCount()}")
                loadMore(settings.initialSize, Timeline.Direction.BACKWARDS)
                RoomOpenTrace.stageFor(roomId, "sdk.initialLoadMore.done", "built=${builtEvents.size}")
            }
        }
    }

    /**
     * The room's members, not just the ones whose events happen to be in the timeline. Read receipts
     * arrive for every member who has read, so without this they render as bare user ids.
     */
    private suspend fun loadRoomMembers() {
        val params = LoadRoomMembersTask.Params(roomId, excludeMembership = Membership.LEAVE)
        while (true) {
            try {
                loadRoomMembersTask.execute(params)
                // Receipts already mapped against the members we had render as bare user ids.
                chunkSnapshotCache.clear()
                rebuildSnapshot()
                return
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                // Permission refusals (e.g. a removed room) can never succeed by retrying.
                if (failure is Failure.ServerError && failure.error.code == MatrixError.M_FORBIDDEN) return
                Timber.v(failure, "Failed to load room members in $roomId, retrying in 10s")
                delay(LOAD_MEMBERS_RETRY_DELAY_MS)
            }
        }
    }

    override fun dispose() {
        isStarted.set(false)
        updateRoomSubscription(false)
        timelineInput.listeners.remove(this)
        eventDecryptor.removeOnDecryptedListener(decryptedListener)
        eventDecryptor.destroy()
        observeJob?.cancel()
        sendingJob?.cancel()
        ignoredJob?.cancel()
        annotationsJob?.cancel()
        receiptsJob?.cancel()
        decryptedJob?.cancel()
        decryptionSignalJob?.cancel()
        profileOverridesJob?.cancel()
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
            newestShownEventId = null
            // Same reasoning as the constructor seed: right after a jump the transient few-row list fits
            // on screen, so the fragment's scroll hint briefly reports "at live edge" — if believed, the
            // live-edge cap clips the target straight back out of the window.
            viewAtLiveEdge = eventId == null
            // Not resolveSeedChunkId(): in a room opened at a permalink it prefers initialEventId's
            // chunk, so a jump-to-live would land back on the search hit instead of the live edge.
            val seed = when {
                eventId != null -> chunkForEvent(eventId) ?: resolveSeedChunkId()
                threadRootId != null -> stores.chunk.lastForwardThread(roomId, threadRootId!!)?.id
                else -> stores.chunk.lastForward(roomId)?.id
            }
            seedFrom(seed)
            rebuildSnapshot()
        }
    }

    override suspend fun restartAtRoomStart(targetEventId: String?): String? = withContext(readDispatcher) {
        // Cheapest first: the first event may already be loaded. Otherwise ask the server for the room's
        // earliest event and resolve a chunk around it. Only then fall back to /context on the create
        // event — that fails outright on room v12, where its id is the room hash — and finally to the
        // oldest event we already hold.
        var anchor = targetEventId?.takeIf { stores.chunk.findChunkIdIncludingEvent(roomId, it) != null }
        if (anchor == null) {
            anchor = tryOrNull("SqlTimeline $roomId room-start fetch failed") {
                fetchRoomStartTask.execute(FetchRoomStartTask.Params(roomId, expectedFirstEventId = targetEventId))
            }?.takeIf { chunkForEvent(it) != null }
        }
        if (anchor == null) {
            anchor = targetEventId?.takeIf { chunkForEvent(it) != null }
        }
        var seedChunk = anchor?.let { stores.chunk.findChunkIdIncludingEvent(roomId, it) }
        if (seedChunk == null) {
            seedChunk = oldestLoadedChunkId()
            anchor = seedChunk?.let { oldestEventIdInChunk(it) }
        }
        pendingShowEventId = anchor
        oldestShownEventId = null
        newestShownEventId = null
        viewAtLiveEdge = false
        seedFrom(seedChunk ?: resolveSeedChunkId())
        rebuildSnapshot()
        anchor
    }

    /** The range holding the room's oldest locally stored history — where a jump to the room start lands
     *  without a server round-trip. The one that reached the start if there is one, else the oldest by span. */
    private fun oldestLoadedChunkId(): Long? {
        val ranges = stores.chunk.getByRoom(roomId).filter { it.root_thread_event_id == null }
        ranges.firstOrNull { it.is_last_backward != 0L }?.let { return it.id }
        return ranges.minByOrNull { stores.timelineEvent.minTsForChunk(it.id) ?: Long.MAX_VALUE }?.id
    }

    // Rows are read newest-first, so the oldest is the minimum timestamp.
    private fun oldestEventIdInChunk(chunkId: Long): String? =
            stores.timelineEvent.getByChunk(chunkId).minByOrNull { it.ts }?.eventId

    override fun setViewAtLiveEdge(atLiveEdge: Boolean) {
        viewAtLiveEdge = atLiveEdge
    }

    // A reseed is asynchronous and the rebuilds that trigger it keep coming; without this they pile up
    // and cancel one another (see rebuildSnapshot).
    private val reseeding = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile private var rebuildsPaused = false
    @Volatile private var rebuildPendingWhilePaused = false

    override fun setPaused(paused: Boolean) {
        rebuildsPaused = paused
        updateRoomSubscription(!paused)
        if (!paused && rebuildPendingWhilePaused) {
            rebuildPendingWhilePaused = false
            timelineScope.launch {
                chunkSnapshotCache.clear()
                rebuildSnapshot()
            }
        }
    }

    private fun updateRoomSubscription(active: Boolean) {
        val shouldSubscribe = active && isStarted.get()
        if (shouldSubscribe && hasRoomSubscription.compareAndSet(false, true)) {
            slidingSyncRoomSubscriptions.acquire(roomId)
        } else if (!shouldSubscribe && hasRoomSubscription.compareAndSet(true, false)) {
            slidingSyncRoomSubscriptions.release(roomId)
        }
    }

    override fun hasMoreToLoad(direction: Timeline.Direction): Boolean = getPaginationState(direction).hasMoreToLoad

    override fun paginate(direction: Timeline.Direction, count: Int) {
        timelineScope.launch { loadMore(count, direction) }
    }

    override suspend fun awaitPaginate(direction: Timeline.Direction, count: Int): List<TimelineEvent> {
        // On the read dispatcher like every other loadMore: the window bookkeeping it mutates is confined there.
        withContext(readDispatcher) { loadMore(count, direction) }
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
                stores.chunk.insert(roomId, null, null, isLastForward = false, isLastBackward = false, rootThreadEventId = rootId, isLastForwardThread = true)
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
        receiptsJob?.cancel()
        loadedChunkIds.clear()
        chunkSnapshotCache.clear()
        liveChunkRowCap = liveChunkRowStep
        liveChunkFullyMapped = false
        liveEdgeLoaded = false
        if (seedChunkId == null) return
        loadedChunkIds.add(seedChunkId)
        // conflate: collapse a burst of row changes into one rebuild (each rebuild reads the latest state).
        observeJob = timelineScope.launch {
            snapshotLoader.chunkChangesFlow(seedChunkId).conflate().collect {
                // A redaction rewrites older event content in place; nothing else can invalidate a
                // cached slice, since nothing else moves a row.
                if (consumeRedactionStamp()) chunkSnapshotCache.clear()
                rebuildSnapshot()
            }
        }
        // NOT reuseLiveChunk: a successful send deletes the echo row *because* the synced event just
        // landed in the live chunk, so reusing the cached (pre-insert) mapping showed neither — the
        // message blinked out until the next chunk rebuild. The live-chunk refresh is incremental anyway.
        sendingJob = timelineScope.launch {
            snapshotLoader.sendingChangesFlow(roomId).conflate().collect { rebuildSnapshot() }
        }
        // An ignore-set change doesn't touch any row, so the cached mapping still stands.
        ignoredJob = timelineScope.launch {
            snapshotLoader.ignoredUserIdsFlow().drop(1).collect { rebuildSnapshot(reuseLiveChunk = true) }
        }
        // Reactions/edits live in event_annotations_summary, which the chunk flow doesn't watch — refresh
        // the events whose aggregations changed when a summary changes.
        annotationsJob = timelineScope.launch {
            // Baseline, not drop(1): the first emission is the current state, which by definition changed
            // nothing — but it has to be recorded to diff the next one against.
            var previous: Map<String, String>? = null
            snapshotLoader.annotationSummaryChangesFlow(roomId).distinctUntilChanged().conflate().collect { current ->
                val before = previous
                previous = current
                if (before == null) return@collect
                val changed = (before.keys + current.keys).filterTo(HashSet()) { before[it] != current[it] }
                if (changed.isEmpty()) return@collect
                // Marker: reaction/edit propagation into the visible timeline (must fire and stay
                // fast regardless of scroll position or the live-edge window cap).
                val perfStart = MatrixPerf.now()
                // Re-map only the events that changed, in place. Dropping the chunk instead (what this used
                // to do for every chunk) forces a full reload+re-map of the whole live chunk, which at the
                // live edge — where there is only one loaded chunk — means re-mapping the entire timeline
                // for a single reaction.
                val remapped = remapCachedEvents(changed)
                rebuildSnapshot()
                MatrixPerf.end(perfStart) { "timeline.annotationsPropagate changed=${changed.size} remapped=$remapped" }
            }
        }
        // A sync carrying only an m.receipt writes neither timeline_event nor the annotation
        // summaries, so without this the receipts stay frozen at the moment each event was mapped.
        // distinctUntilChanged AFTER drop(1): a receipt landing between the table listener registering
        // and the first query running yields two identical fingerprints, and de-duplicating first would
        // collapse them into the initial emission that drop(1) discards.
        receiptsJob = timelineScope.launch {
            var previous: List<String>? = null
            snapshotLoader.readReceiptChangesFlow(roomId).drop(1).distinctUntilChanged().conflate().collect { current ->
                val before = previous
                previous = current
                // Rows are "eventId|userId|ts|profile"; a receipt that moved appears on both sides of the
                // difference, so this names the event it left and the one it landed on. Re-mapping just
                // those beats dropping the cache: that re-maps every loaded row for one receipt, which in a
                // scrolled-back room is thousands of events and about a second of work.
                val changed = if (before == null) {
                    current.mapTo(HashSet()) { it.substringBefore('|') }
                } else {
                    val beforeRows = before.toSet()
                    val currentRows = current.toSet()
                    (beforeRows - currentRows).plus(currentRows - beforeRows).mapTo(HashSet()) { it.substringBefore('|') }
                }
                if (changed.isEmpty()) return@collect
                val perfStart = MatrixPerf.now()
                val remapped = remapCachedEvents(changed)
                rebuildSnapshot()
                MatrixPerf.end(perfStart) { "timeline.receiptsPropagate changed=${changed.size} remapped=$remapped" }
            }
        }
    }

    /** Events these users' profiles are displayed on: their own, plus any carrying a read receipt of theirs. */
    private fun remapCachedEventsOfUsers(userIds: Set<String>) {
        if (userIds.isEmpty()) return
        val eventIds = HashSet<String>()
        chunkSnapshotCache.values.forEach { events ->
            events.forEach { event ->
                if (event.senderInfo.userId in userIds || event.readReceipts.any { it.roomMember.userId in userIds }) {
                    eventIds.add(event.eventId)
                }
            }
        }
        remapCachedEvents(eventIds)
    }

    /** Re-map the given events in place, leaving every other cached mapping alone. @return how many moved. */
    private fun remapCachedEvents(changed: Set<String>): Int {
        var remapped = 0
        for (chunkId in chunkSnapshotCache.keys.toList()) {
            val events = chunkSnapshotCache[chunkId] ?: continue
            if (events.none { it.eventId in changed }) continue
            chunkSnapshotCache[chunkId] = events.map { event ->
                if (event.eventId !in changed) event
                else snapshotLoader.reloadEvent(roomId, event.eventId)?.also { remapped++ } ?: event
            }
        }
        return remapped
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
                val oldestPrevToken = oldest.prev_token
                when {
                    // is_last_backward is the room start: there is nothing older to ask for.
                    oldest.is_last_backward != 0L -> if (isWindowed) rebuildSnapshot(reuseLiveChunk = true) else updateState(Timeline.Direction.BACKWARDS) { it.copy(hasMoreToLoad = false) }
                    oldestPrevToken != null -> {
                        val page = paginate(oldestPrevToken, Timeline.Direction.BACKWARDS, count, oldest.id)
                        val gapped = page.gapDetected
                        invalidateAfterServerPage(rowsMoved = page.rowsMoved)
                        // Wait for healing before allowing the loading row to request the same refused page again.
                        if (gapped && !healBoundary(oldest.id) && !frontierStalled(oldest.id)) {
                            DebugLog.i { "GAPDBG $roomId: nothing more reachable below range ${oldest.id}, stopping the backward load" }
                            stalledFrontier = withContext(sessionDispatcher) { boundaryKey(oldest.id) }
                            updateState(Timeline.Direction.BACKWARDS) { it.copy(hasMoreToLoad = false) }
                        }
                        // The page is written into this range, and any range it turned out to overlap has
                        // been folded into it, so the older history is simply part of the range now.
                        revealAfterBackwardFetch()
                    }
                    // A split has no token into its gap. If timestamp healing fails, include the older range
                    // so its history remains reachable despite the visible timestamp jump.
                    else -> {
                        val healed = healBoundary(oldest.id)
                        if (!healed) {
                            val below = withContext(sessionDispatcher) { stores.chunk.rangeBelow(roomId, oldest.id) }
                            if (below != null) {
                                DebugLog.w { "GAPDBG $roomId: cannot fill the hole under range ${oldest.id}, taking #$below in so its history is reachable" }
                                withContext(sessionDispatcher) {
                                    database.awaitDbTransaction(sessionDispatcher) {
                                        // Remember it, or the next room open splits the same gap straight
                                        // back out and strands that history again.
                                        stores.timelineEvent.maxTsForChunk(below)?.let { stores.chunk.markGapUnfillable(roomId, it) }
                                        stores.chunk.mergeInto(oldest.id, below)
                                    }
                                    invalidateAfterServerPage()
                                }
                                revealAfterBackwardFetch()
                                return
                            }
                            updateState(Timeline.Direction.BACKWARDS) { it.copy(hasMoreToLoad = false) }
                        }
                        if (isWindowed) rebuildSnapshot(reuseLiveChunk = true)
                    }
                }
            } finally {
                backwardPaginating.set(false)
            }
        } else {
            // The visible forward spinner fires this continuously; without a guard the duplicate
            // server round-trips clog the serial timeline queue (delaying jumps scheduled behind them).
            if (!forwardPaginating.compareAndSet(false, true)) return
            try {
                // Forward-bounded window (jumped deep into history): reveal already-loaded newer events
                // before touching the chunk chain, mirroring the backward reveal.
                if (isWindowed && newestShownEventId != null) {
                    val all = computeLoadedEvents(reuseLiveChunk = true)
                    val curIdx = all.indexOfFirst { it.eventId == newestShownEventId }
                    if (curIdx > 0) {
                        val newIdx = retreatByMessages(all, curIdx, windowGrowStep).coerceAtLeast(0)
                        // Only drop the bound at the true live edge. Clearing it just because we reached
                        // the newest *loaded* event re-expands the window to the full loaded prefix on
                        // the next forward page — thousands of events again, and everything slows down.
                        newestShownEventId = if (newIdx == 0 && liveEdgeLoaded) null else all[newIdx].eventId
                        rebuildSnapshot(reuseLiveChunk = true)
                        return
                    }
                    if (liveEdgeLoaded) newestShownEventId = null
                }
                val newest = loadedChunkIds.firstOrNull()?.let { stores.chunk.getById(it) } ?: return
                val newestNextToken = newest.next_token
                when {
                    newest.is_last_forward != 0L -> updateState(Timeline.Direction.FORWARDS) { it.copy(hasMoreToLoad = false) }
                    newestNextToken != null -> {
                        val page = paginate(newestNextToken, Timeline.Direction.FORWARDS, count, newest.id)
                        invalidateAfterServerPage(rowsMoved = page.rowsMoved)
                        rebuildSnapshot()
                    }
                    else -> updateState(Timeline.Direction.FORWARDS) { it.copy(hasMoreToLoad = false) }
                }
            } finally {
                forwardPaginating.set(false)
            }
        }
    }

    private fun boundaryKey(chunkId: Long): String? {
        val ourOldest = stores.timelineEvent.minTsForChunk(chunkId) ?: return null
        return "$chunkId|$ourOldest"
    }

    // The frontier a refused backward page gave up on, keyed like a boundary: anything that later fills
    // in under that chunk moves its oldest timestamp, which lifts the stall and lets loading resume.
    private var stalledFrontier: String? = null

    private suspend fun frontierStalled(chunkId: Long): Boolean =
            stalledFrontier != null && stalledFrontier == withContext(sessionDispatcher) { boundaryKey(chunkId) }

    // A page can extend the chunk it was fetched from, or make the persistor absorb one chunk into
    // another, so the cached mappings (and the ids we hold) can describe rows that have moved or a
    // chunk that is gone.
    //
    // [rowsMoved] false means the page only appended history below what is mapped, which the cached slices
    // still describe correctly — dropping them there costs a full re-map of the whole window per page, the
    // single largest cost of a long backward scroll.
    private fun invalidateAfterServerPage(rowsMoved: Boolean = true) {
        // A page can land under a boundary and move it, which invalidates what a walk concluded about it.
        stores.chunk.forgetUnhealableBoundaries(roomId)
        if (rowsMoved) chunkSnapshotCache.clear()
        liveChunkFullyMapped = false
        val alive = loadedChunkIds.filterTo(LinkedHashSet()) { stores.chunk.getById(it) != null }
        if (alive.size != loadedChunkIds.size) {
            loadedChunkIds.clear()
            loadedChunkIds.addAll(alive)
        }
    }

    private suspend fun loadMoreThread(count: Int, direction: Timeline.Direction) {
        if (direction == Timeline.Direction.FORWARDS) {
            updateState(Timeline.Direction.FORWARDS) { it.copy(hasMoreToLoad = false) }
            return
        }
        // Overlapping calls would re-fetch the same prevToken; the loading item fires this continuously.
        if (!backwardPaginating.compareAndSet(false, true)) return
        try {
            val threadChunkId = loadedChunkIds.firstOrNull() ?: return
            val prevToken = stores.chunk.getById(threadChunkId)?.prev_token
            updateState(Timeline.Direction.BACKWARDS) { it.copy(loading = true) }
            val result = tryOrNull("SqlTimeline $roomId thread pagination failed") {
                fetchThreadTimelineTask.execute(FetchThreadTimelineTask.Params(roomId, threadRootId!!, prevToken, count))
            }
            val reachedEnd = result == DefaultFetchThreadTimelineTask.Result.REACHED_END
            updateState(Timeline.Direction.BACKWARDS) { it.copy(loading = false, hasMoreToLoad = !reachedEnd) }
            rebuildSnapshot()
        } finally {
            backwardPaginating.set(false)
        }
    }

    /** What a round of paging did, beyond the rows it wrote. */
    private class PageOutcome(val gapDetected: Boolean, val rowsMoved: Boolean)

    private suspend fun paginate(token: String, direction: Timeline.Direction, count: Int, originChunkId: Long? = null): PageOutcome {
        var gapDetected = false
        var rowsMoved = false
        updateState(direction) { it.copy(loading = true) }
        try {
            // Keep fetching within one user-visible round until real progress is made:
            // - SHOULD_FETCH_MORE = the page made token-progress only (invisible span, boundary
            //   overlap): follow the origin chunk's token as the persistor slides it.
            // - a SUCCESS page can still be almost entirely overlap-skipped duplicates (server token
            //   paths re-covering stored regions); stopping there dribbles one or two events per
            //   scroll, so follow the landed chunk's far token until enough new rows accumulated.
            var from = token
            var origin = originChunkId
            var rounds = 0
            var newRows = 0
            while (rounds++ < MAX_PAGINATION_ROUNDS) {
                val stats = TokenChunkEventPersistor.PageWriteStats()
                val result = paginationTask.execute(
                        PaginationTask.Params(roomId, from, toPaginationDirection(direction), count, origin, stats, serverGapProbe = true)
                )
                newRows += stats.written
                rowsMoved = rowsMoved || stats.rowsMoved
                // Folding in a range this page proved we already hold reveals history without writing a
                // row: real progress, and the round must end or the walk re-fetches the same page.
                if (stats.folded > 0) break
                if (result == TokenChunkEventPersistor.Result.REACHED_END) break
                // A detected gap ends the round: its recovery decides how the walk continues.
                if (stats.gapDetected) {
                    gapDetected = true
                    break
                }
                val followChunkId = if (result == TokenChunkEventPersistor.Result.SHOULD_FETCH_MORE) origin else {
                    if (newRows >= minOf(count, MIN_NEW_ROWS_PER_LOAD)) break
                    stats.landedChunkId
                }
                val follow = followChunkId?.let { withContext(sessionDispatcher) { stores.chunk.getById(it) } } ?: break
                if (direction == Timeline.Direction.BACKWARDS && follow.is_last_backward != 0L) break
                if (direction == Timeline.Direction.FORWARDS && follow.is_last_forward != 0L) break
                val next = (if (direction == Timeline.Direction.BACKWARDS) follow.prev_token else follow.next_token) ?: break
                if (next == from) break
                from = next
                origin = follow.id
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            // A removed (kicked/banned) room hit the limit of what the server will serve a departed
            // user. Persist it as the end of the room so the UI stops re-requesting an eternal
            // loading row; the once-per-open reprobe above keeps it from being final.
            if (direction == Timeline.Direction.BACKWARDS && originChunkId != null &&
                    failure is Failure.ServerError && failure.error.code == MatrixError.M_FORBIDDEN &&
                    stores.room.get(roomId)?.membership != Membership.JOIN) {
                database.awaitDbTransaction(sessionDispatcher) { stores.chunk.setLastBackward(originChunkId, true) }
                updateState(Timeline.Direction.BACKWARDS) { it.copy(hasMoreToLoad = false) }
            }
            Timber.w(failure, "SqlTimeline $roomId pagination failed")
        }
        updateState(direction) { it.copy(loading = false) }
        return PageOutcome(gapDetected = gapDetected, rowsMoved = rowsMoved)
    }

    // Keep visible event anchors while healing moves stored history.
    private val windowPinCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val windowPinned: Boolean get() = windowPinCount.get() > 0

    /** Prevent automatic window expansion during healing while preserving explicit user navigation. */
    private suspend fun <T> withPinnedWindow(block: suspend () -> T): T {
        windowPinCount.incrementAndGet()
        return try {
            block()
        } finally {
            windowPinCount.decrementAndGet()
        }
    }

    /** Returns whether timestamp healing recovered history below the boundary. */
    private suspend fun healBoundary(strandedChunkId: Long): Boolean {
        val changed = withPinnedWindow { healBoundaryPinned(strandedChunkId) }
        // Rebuild against the healed ranges after releasing this window pin.
        rebuildSnapshot()
        return changed
    }

    private suspend fun healBoundaryPinned(strandedChunkId: Long): Boolean {
        if (isThreadTimeline) return false
        // Per boundary, not one lock for the room: a jump heals the boundary above the target and the one
        // below it, and making them queue behind each other left whichever lost the race unhealed.
        val key = withContext(sessionDispatcher) { boundaryKey(strandedChunkId) } ?: return false
        // Asked and answered this session: the walk's two round trips would only delay the reveal again.
        if (withContext(sessionDispatcher) { stores.chunk.isBoundaryUnhealable(roomId, key) }) {
            DebugLog.i { "GAPDBG $roomId: boundary under $strandedChunkId already known unhealable, not walking again" }
            return false
        }
        if (!healsInFlight.add(key)) return false
        try {
            DebugLog.i { "GAPDBG $roomId: healing the boundary under chunk $strandedChunkId" }
            val filled = gapHealer.fillBackwardByTimestamp(roomId, strandedChunkId)
            if (filled > 0) {
                withContext(sessionDispatcher) { invalidateAfterServerPage() }
                rebuildSnapshot()
                return true
            }

            DebugLog.i { "GAPDBG $roomId: timestamp walk filled nothing under $strandedChunkId" }
            withContext(sessionDispatcher) { stores.chunk.markBoundaryUnhealable(roomId, key) }
            return false
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            Timber.w(failure, "SqlTimeline $roomId boundary heal failed")
            return false
        } finally {
            healsInFlight.remove(key)
        }
    }

    private fun toPaginationDirection(direction: Timeline.Direction) =
            if (direction == Timeline.Direction.FORWARDS) PaginationDirection.FORWARDS else PaginationDirection.BACKWARDS

    private fun computeLoadedEvents(reuseLiveChunk: Boolean = false): List<TimelineEvent> {
        val liveChunkId = loadedChunkIds.firstOrNull()
        val liveEdge = isLiveEdgeLoaded()
        // Read the sending rows BEFORE the chunk. The sync that inserts the synced event also deletes the
        // echo row, so reading the chunk first let a rebuild straddle that commit — chunk read before it,
        // sending read after — leaving the message in neither, invisible until the next rebuild. Reading in
        // this order can only ever yield the echo twice, which the synced-transaction filter below removes.
        val dbSending = if (isThreadTimeline || liveEdge) snapshotLoader.sendingEvents(roomId) else emptyList()
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
        // Runs when nothing is sending too. The reacted-on message's echo is already gone by the time its
        // synced row arrives, and that row is what re-keys any reaction echo filed under the old id.
        val syncedTxnIds = if (dbSending.isEmpty() && uiEchoManager.getInMemorySendingEvents().isEmpty() &&
                !uiEchoManager.hasPendingReactionEchoes()) {
            emptySet()
        } else {
            chunkEvents.mapNotNullTo(HashSet()) { event ->
                event.root.unsignedData?.transactionId?.also { txnId ->
                    uiEchoManager.onEchoResolved(txnId, event.eventId)
                    uiEchoManager.onSyncedEvent(txnId)
                }
            }
        }
        val sending = if (isThreadTimeline || liveEdge) {
            uiEchoManager.onSentEventsInDatabase(dbSending.map { it.eventId })
            (uiEchoManager.getInMemorySendingEvents() + dbSending)
                    .distinctBy { it.eventId }
                    // Already in the chunk under its synced id — keeping the echo too would show it twice.
                    .filterNot { it.eventId in syncedTxnIds }
                    // Only the local echoes posted into this thread belong at its live edge.
                    .let { if (isThreadTimeline) it.filter { e -> e.root.getRootThreadEventId() == threadRootId } else it }
                    .map { uiEchoManager.updateSentStateWithUiEcho(it) }
        } else {
            emptyList()
        }
        // Hide everything an ignored user did at display time — their joins, leaves and ACL changes as
        // much as their messages. The events are still stored and still applied to room state; only the
        // timeline tiles go. Filtering here rather than deleting rows is what makes un-ignore instant.
        val ignored = stores.user.getIgnoredUserIds().toSet()
        return (sending + chunkEvents)
                .filterNot { it.root.senderId in ignored }
                .map { uiEchoManager.decorateEventWithReactionUiEcho(it) }
    }

    // Index 0 is newest. Keep newest down to [oldestShownEventId], growing the anchor
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
                // Bound the newer side too: a deep target with an unbounded newer side means mapping
                // and model-building everything up to the live edge at once. Newer events reveal
                // step-wise through forward pagination instead. Always set — the target often resolves
                // against a barely-loaded set (idx 0 of 1 event, context still fetching), and a null
                // bound here re-expands over everything the context fetch brings in. If the target is
                // actually near the live edge, the first reveal clears the bound (liveEdgeLoaded).
                newestShownEventId = all[(idx - initialWindowCount()).coerceAtLeast(0)].eventId
            }
        }
        val anchorIdx = oldestShownEventId?.let { id -> all.indexOfFirst { it.eventId == id } }
        // Keep unresolved anchors during healing to avoid moving the visible window.
        // A null anchor still means the live window and should render normally.
        if (windowPinned && oldestShownEventId != null && (anchorIdx == null || anchorIdx < 0)) return builtEvents
        var oldestIdx = (anchorIdx?.takeIf { it >= 0 } ?: (initialWindowCount() - 1)).coerceIn(0, all.lastIndex)
        // Cap the live-edge window by the count of *message* events, not raw events: a flood of redactions
        // or state changes (e.g. a mass redaction) collapses to a single merged item, so a raw cap would
        // show that one block and nothing else — no content, no scroll affordance to grow the window.
        val capIdx = if (viewAtLiveEdge && pendingShowEventId == null && newestShownEventId == null) contentWindowCapIndex(all) else all.lastIndex
        if (oldestIdx > capIdx) {
            oldestIdx = capIdx
        }
        oldestShownEventId = all[oldestIdx].eventId
        val boundIdx = newestShownEventId?.let { id -> all.indexOfFirst { it.eventId == id } } ?: -1
        // A bound sitting at index 0 (revealed up to the newest loaded event, next page not fetched
        // yet) must be KEPT — nulling it would re-expand the window over every event the next page
        // brings in. Only an id that vanished from the loaded set clears the bound.
        val newestIdx = boundIdx.coerceAtMost(oldestIdx).coerceAtLeast(0)
        // Keep missing bounds while pinned so healing cannot expand the visible window.
        newestShownEventId = when {
            boundIdx >= 0 -> all[newestIdx].eventId
            windowPinned -> newestShownEventId
            else -> null
        }
        return ArrayList(all.subList(newestIdx, oldestIdx + 1))
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

    // Mirror of [advanceByMessages] toward newer events (index decreasing), for the forward reveal.
    // Additionally bounded by a raw event count: in an edit-heavy room [messageStep] messages can span
    // hundreds of raw events, each mapped + model-built on reveal — an unbounded step made every
    // reveal a multi-second stall. If the capped step doesn't fill the screen, the still-visible
    // spinner just fires the next one.
    private fun retreatByMessages(all: List<TimelineEvent>, fromIdx: Int, messageStep: Int): Int {
        val rawFloor = (fromIdx - MAX_RAW_REVEAL_STEP).coerceAtLeast(0)
        var messages = 0
        var i = fromIdx - 1
        while (i >= rawFloor) {
            if (all[i].isMessageContent() && ++messages >= messageStep) return i
            i--
        }
        return rawFloor
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

    private suspend fun rebuildSnapshot(reuseLiveChunk: Boolean = false, force: Boolean = false) {
        if (rebuildsPaused && !force) {
            rebuildPendingWhilePaused = true
            return
        }
        val perfStart = MatrixPerf.now()
        val nowAtLiveEdge = isLiveEdgeLoaded()
        // A forced new live chunk (sliding-sync initial redelivery, rejoin) demotes the chunk we were
        // watching without deleting it; new events land in the new chunk, so follow it. Only an edge
        // we actually held counts: a jump into history never starts from a last-forward chunk.
        if (liveEdgeLoaded && !nowAtLiveEdge && !isThreadTimeline) {
            val newLive = stores.chunk.lastForward(roomId)?.id
            if (newLive != null && newLive != loadedChunkIds.firstOrNull()) {
                // Only one reseed may run; another launch would cancel the observer still seeding the first.
                if (reseeding.compareAndSet(false, true)) {
                    // Not inline: seedFrom cancels the observer job this rebuild usually runs in.
                    timelineScope.launch {
                        try {
                            reseedAtLiveEdge(newLive)
                        } finally {
                            reseeding.set(false)
                        }
                    }
                }
                return
            }
        }
        liveEdgeLoaded = nowAtLiveEdge
        // A merge retires the range it folds away, moving its rows to the survivor. Drop ids that no longer
        // exist so the recovery below re-seeds where that history went — reading a dead range renders
        // nothing, and nothing else would ever reseed.
        if (!isThreadTimeline && loadedChunkIds.isNotEmpty()) {
            val alive = loadedChunkIds.filterTo(LinkedHashSet()) { stores.chunk.getById(it) != null }
            if (alive.size != loadedChunkIds.size) {
                Timber.w("SqlTimeline $roomId: range(s) ${loadedChunkIds - alive} were merged away, re-seeding")
                loadedChunkIds.clear()
                loadedChunkIds.addAll(alive)
                chunkSnapshotCache.clear()
            }
        }
        if (loadedChunkIds.isEmpty() && !isThreadTimeline) {
            // Not inline: seedFrom cancels the observer job this rebuild usually runs in. This pass still
            // posts its (empty) snapshot — a room that genuinely has no chunk yet must keep doing that.
            timelineScope.launch { if (recoverLostSeed()) rebuildSnapshot() }
        }
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
        windowHasMoreNewer = isWindowed && events.isNotEmpty() && events.first().eventId != all.first().eventId
        // The loading spinners are (re)built from hasMoreToLoad only when a snapshot is posted, so a
        // pagination-state flip that doesn't change the visible events (reaching the room start reveals the
        // empty is_last_backward chunk) must still post — otherwise the backward spinner is never removed and
        // its visibility listener re-fires onLoadMore forever.
        val backwardBefore = backwardState.get().hasMoreToLoad
        val forwardBefore = forwardState.get().hasMoreToLoad
        refreshPaginationStates()
        val paginationChanged = backwardBefore != backwardState.get().hasMoreToLoad || forwardBefore != forwardState.get().hasMoreToLoad
        Timber.v("SqlTimeline $roomId rebuilt snapshot of ${events.size}/${all.size} events (unchanged=$unchanged)")
        MatrixPerf.end(perfStart) { "timeline.rebuildSnapshot reuse=$reuseLiveChunk shown=${events.size}/${all.size} unchanged=$unchanged" }
        if (!unchanged || paginationChanged) {
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

    /** Append rows after the cached timestamp cursor; content mutations invalidate the cache separately. */
    private fun refreshLiveChunkSnapshot(chunkId: Long): List<TimelineEvent> {
        val cached = chunkSnapshotCache[chunkId]
        if (cached.isNullOrEmpty()) {
            return loadLiveChunkNewest(chunkId).also { chunkSnapshotCache[chunkId] = it }
        }
        val newest = cached.first()
        val oldest = cached.last()
        val newEvents = snapshotLoader.chunkSnapshotAfter(chunkId, newest.root.originServerTs ?: 0L, newest.eventId)
        // Counted from the slice's own oldest row rather than over the whole chunk: an event that landed
        // inside the mapped window (or was removed from it) shows up as a count that no longer matches, while
        // a backward page — which only ever appends BELOW that row — leaves the slice intact and cheap.
        val countFromOldest = snapshotLoader.chunkEventCountFrom(chunkId, oldest.root.originServerTs ?: 0L, oldest.eventId)
        if (countFromOldest != (cached.size + newEvents.size).toLong() ||
                newEvents.any { it.root.getClearType() == EventType.REDACTION }) {
            loadedChunkIds.forEach { if (it != chunkId) chunkSnapshotCache.remove(it) }
            return loadLiveChunkNewest(chunkId).also { chunkSnapshotCache[chunkId] = it }
        }
        if (newEvents.isEmpty()) return cached
        return (newEvents + cached).also { chunkSnapshotCache[chunkId] = it }
    }

    // Map only the newest [liveChunkRowCap] rows of the live chunk (unless a permalink target is pending —
    // that event may sit deep in the chunk, so map it whole to be sure it's reachable).
    private fun loadLiveChunkNewest(chunkId: Long): List<TimelineEvent> {
        val storedCount = snapshotLoader.chunkEventCount(chunkId)
        // Threads aren't windowed (they page from the server), and a permalink target may sit deep in the
        // chunk — map the whole chunk in both cases so nothing is unreachable.
        if (isThreadTimeline || pendingShowEventId != null) {
            liveChunkFullyMapped = true
            return snapshotLoader.chunkSnapshot(chunkId)
        }
        val slice = snapshotLoader.chunkSnapshotNewest(chunkId, liveChunkRowCap.toLong())
        liveChunkFullyMapped = slice.size >= storedCount
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
        val oldest = cached.last()
        val older = snapshotLoader.chunkSnapshotOlderThan(
                liveChunkId, oldest.root.originServerTs ?: 0L, oldest.eventId, liveChunkRowStep.toLong()
        )
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

    // A thread chunk carries its live edge in is_last_forward_thread; is_last_forward is the room's.
    private fun isLiveEdgeLoaded(): Boolean =
            loadedChunkIds.firstOrNull()
                    ?.let { stores.chunk.getById(it) }
                    ?.let { if (isThreadTimeline) it.is_last_forward_thread else it.is_last_forward } == 1L

    private fun refreshPaginationStates() {
        // Thread pagination state is driven directly by fetchThreadTimelineTask results in loadMoreThread.
        if (isThreadTimeline) return
        val oldest = loadedChunkIds.lastOrNull()?.let { stores.chunk.getById(it) }
        val moreBackward = windowHasMoreOlder ||
                (oldest != null && oldest.is_last_backward == 0L &&
                        // A split range can have older stored history even without a pagination token.
                        (oldest.prev_token != null || stores.chunk.rangeBelow(roomId, oldest.id) != null))
        updateState(Timeline.Direction.BACKWARDS) { it.copy(hasMoreToLoad = moreBackward) }

        val newest = loadedChunkIds.firstOrNull()?.let { stores.chunk.getById(it) }
        val moreForward = windowHasMoreNewer || (newest != null && newest.is_last_forward == 0L && newest.next_token != null)
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
            // Forward-bounded window: the live edge isn't shown, so prepending the echo would place
            // it next to old history. Sending triggers a jump-to-bottom restart which shows it.
            if (!isThreadTimeline && (!liveEdgeLoaded || newestShownEventId != null)) return@launch
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

    override fun onLocalEchoDeleted(roomId: String, eventId: String) {
        if (roomId != this.roomId || !isStarted.get()) return
        timelineScope.launch(coroutineDispatchers.main) {
            // Also drops any stranded in-memory copy, its send-state override and its reaction echo.
            uiEchoManager.onSyncedEvent(eventId, dropReactionEcho = true)
            val current = builtEvents
            val idx = current.indexOfFirst { it.eventId == eventId }
            if (idx < 0) return@launch
            builtEvents = current.toMutableList().also { it.removeAt(idx) }
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
            val currentLive = loadedChunkIds.firstOrNull()
            // This fires from inside the sync transaction, so the chunk rewrite isn't committed yet and a
            // read thread would still see the old chunk. Hopping to the write dispatcher queues behind the
            // transaction, which is exactly the barrier we need.
            val liveChunkId = withContext(sessionDispatcher) {
                // Re-seed only when our chunk was deleted (the limited-sync case above), or when nothing was
                // ever seeded because the room had no chunk at open time — seedFrom() registers no observer
                // then, leaving the timeline empty until reopened. A jump into history legitimately leaves the
                // newest loaded chunk behind the last-forward one; re-seeding there would yank the user back
                // to the live edge on every incoming message.
                if (currentLive != null && stores.chunk.getById(currentLive) != null) null
                else stores.chunk.lastForward(this@SqlTimeline.roomId)?.id
            } ?: return@launch
            reseedAtLiveEdge(liveChunkId)
        }
    }

    /**
     * Re-seed after the chunk we were watching was deleted underneath us: a jump-to-event seeds on a
     * lone-event island, and a page still in flight from an earlier jump absorbs any island it re-covers.
     * The rows move into the absorbing chunk rather than disappearing, so follow the anchor event there —
     * without this the timeline stays empty until the room is reopened.
     */
    private suspend fun recoverLostSeed(): Boolean {
        if (isThreadTimeline || loadedChunkIds.isNotEmpty()) return false
        val anchor = pendingShowEventId ?: newestShownEventId ?: oldestShownEventId
        // Main ranges only: a thread range holds copies of the same events and is not a timeline seed.
        val chunkId = anchor?.let { stores.chunk.findMainChunkIdIncludingEvent(roomId, it) }
                ?: stores.chunk.lastForward(roomId)?.id
                ?: return false
        Timber.w("SqlTimeline $roomId lost its seed chunk, re-seeding on $chunkId at $anchor")
        pendingShowEventId = anchor
        oldestShownEventId = null
        newestShownEventId = null
        seedFrom(chunkId)
        return true
    }

    private suspend fun reseedAtLiveEdge(chunkId: Long) {
        pendingShowEventId = null
        oldestShownEventId = null
        newestShownEventId = null
        seedFrom(chunkId)
        rebuildSnapshot()
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
        private const val ROOM_MEMBER_LOAD_DELAY_MS = 5_000L
        private const val LOAD_MEMBERS_RETRY_DELAY_MS = 10_000L

        // Bounds the immediate follow-ups after token-progress-only pages; the UI's loading item
        // re-triggers for anything longer.
        private const val MAX_PAGINATION_ROUNDS = 10

        // A pagination round keeps fetching until at least this many genuinely new rows landed (or
        // the round cap), so near-duplicate pages don't dribble one event per scroll.
        private const val MIN_NEW_ROWS_PER_LOAD = 10
        private const val MAX_RAW_REVEAL_STEP = 150
    }
}
