/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.sliding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.profile.ProfileKeys
import org.matrix.android.sdk.api.session.room.accountdata.RoomAccountDataTypes
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.tag.RoomTag
import org.matrix.android.sdk.api.session.sync.InitialSyncStep
import org.matrix.android.sdk.api.session.sync.RoomSyncScope
import org.matrix.android.sdk.api.session.sync.SyncRequestState
import org.matrix.android.sdk.api.session.sync.model.RoomSync
import org.matrix.android.sdk.api.session.sync.model.SyncResponse
import org.matrix.android.sdk.api.util.MatrixPerf
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.SessionDbPriority
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.TimeOutInterceptor
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.homeserver.HomeServerCapabilitiesDataSource
import org.matrix.android.sdk.internal.session.sync.SyncImportState
import org.matrix.android.sdk.internal.session.sync.SyncPresence
import org.matrix.android.sdk.internal.session.sync.SyncRequestStateTracker
import org.matrix.android.sdk.internal.session.sync.SyncResponseHandler
import org.matrix.android.sdk.internal.session.sync.SyncTask
import org.matrix.android.sdk.internal.session.sync.SyncTokenStore
import org.matrix.android.sdk.internal.session.sync.handler.ShieldSummaryUpdater
import org.matrix.android.sdk.internal.session.sync.reportSubtask
import org.matrix.android.sdk.internal.session.user.UserStore
import org.matrix.android.sdk.internal.settings.DefaultLightweightSettingsStorage
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.exp

private val loggerTag = LoggerTag("SlidingSyncTask", LoggerTag.SYNC)

internal enum class SlidingSyncMode {
    /** MSC4186, driven by one list covering every room. */
    SIMPLIFIED,

    /** MSC4525, driven by server-side paging. */
    PAGINATED,
}

/**
 * Drives the MSC4186 / MSC4525 sync connection. Both answer the same room results, so the only real
 * difference is how the client asks for coverage: MSC4186 widens a list range, MSC4525 drains pages.
 */
@SessionScope
internal class SlidingSyncTask @Inject constructor(
        private val slidingSyncAPI: SlidingSyncAPI,
        private val translator: SlidingSyncTranslator,
        private val syncResponseHandler: SyncResponseHandler,
        private val syncTokenStore: SyncTokenStore,
        private val syncRequestStateTracker: SyncRequestStateTracker,
        private val globalErrorReceiver: GlobalErrorReceiver,
        private val userStore: UserStore,
        private val session: Session,
        private val clock: Clock,
        private val homeServerCapabilitiesDataSource: HomeServerCapabilitiesDataSource,
        private val sessionDbPriority: SessionDbPriority,
        private val shieldSummaryUpdater: ShieldSummaryUpdater,
        private val syncImportState: SyncImportState,
        private val roomSubscriptions: SlidingSyncRoomSubscriptions,
        private val unsyncedRoomTracker: UnsyncedRoomTracker,
        private val settingsStorage: DefaultLightweightSettingsStorage,
        private val stores: SessionStores,
        @UserId private val userId: String,
        private val latestRoomTimelineFetcher: LatestRoomTimelineFetcher,
) {
    // Persist coverage per connection. Start with visible rooms, then widen in large batches.
    private var listRangeEnd = INITIAL_RANGE_SIZE - 1

    // What the request in flight subscribed to as open, and which of those rooms have been caught up since
    // they were opened. Only touched from the sync coroutine.
    private var requestedOpenRooms: Set<String> = emptySet()
    private val caughtUpOpenRooms = HashSet<String>()

    // Rooms the lists sync again that still have to be caught up; while some remain the next request does
    // not long-poll. A room whose fetch failed waits for its next delivery or for being opened instead.
    private var catchUpBacklog = false
    private val failedCatchUps = HashSet<String>()

    // Whether the server still has rooms this connection has not been given. While true the next sync must
    // not long-poll, since there is already something waiting.
    private var coverageIncomplete = false

    // Carried across calls: the space graph is rebuilt once the fill settles, not on every response.
    private var spaceValidationOwed = false

    suspend fun sync(mode: SlidingSyncMode, params: SyncTask.Params): SyncResponse {
        // A connection only ever delivers the state it was opened with, and MSC4525 requires
        // required_state to be identical for its whole life. So when this build asks for something
        // different from the one that opened the connection, the connection has to start over — otherwise
        // rooms keep whatever state the old list happened to cover.
        if (syncTokenStore.getSlidingSyncStateVersion() != STATE_VERSION) {
            Timber.i("SYNCDBG required_state changed (${syncTokenStore.getSlidingSyncStateVersion()} -> $STATE_VERSION), restarting the connection")
            syncTokenStore.setSlidingSyncPos(null)
            syncTokenStore.setSlidingSyncToDeviceSince(null)
            syncTokenStore.setSlidingSyncStateVersion(STATE_VERSION)
        }
        // A pos only means something to the endpoint that issued it. An unrecorded endpoint is left alone: a
        // pos from the other one is rejected and restarted from anyway.
        val storedTransport = syncTokenStore.getSlidingSyncTransport()
        if (storedTransport != mode.name) {
            if (storedTransport != null) {
                Timber.i("SYNCDBG switching from $storedTransport to $mode, restarting the connection")
                syncTokenStore.setSlidingSyncPos(null)
                syncTokenStore.setSlidingSyncToDeviceSince(null)
            }
            syncTokenStore.setSlidingSyncTransport(mode.name)
        }

        // Whatever is still staged belongs to a response that never got its pos stored, and is about to be
        // replayed: it has to find the rooms it was going to un-track still tracked.
        unsyncedRoomTracker.rollback()
        markRoomsJoiningSyncByScope()

        val pos = syncTokenStore.getSlidingSyncPos()
        val isInitialSync = pos == null
        if (isInitialSync) {
            listRangeEnd = INITIAL_RANGE_SIZE - 1
            syncTokenStore.setSlidingSyncCoverage(listRangeEnd)
            coverageIncomplete = false
            syncRequestStateTracker.startRoot(InitialSyncStep.ImportingAccount, 100)
        } else {
            listRangeEnd = syncTokenStore.getSlidingSyncCoverage() ?: listRangeEnd
            syncRequestStateTracker.setSyncRequestState(SyncRequestState.IncrementalSyncIdle)
        }

        val initialSyncReporter = syncRequestStateTracker.takeIf { isInitialSync }

        // One response per call, rather than looping until the account is covered: the sync thread treats
        // this method returning as "the sync finished", and holding it open for the whole fill leaves the
        // spinner up, the thread unable to pause when the app is backgrounded, and every other database
        // user queued behind an unbroken run of writes. Coverage continues on the next call instead.
        val timeout = if (coverageIncomplete || catchUpBacklog) 0L else params.timeout
        Timber.i(
                "SYNCDBG sync start $mode pos=$pos initial=$isInitialSync coverage=0..$listRangeEnd coverageIncomplete=$coverageIncomplete " +
                        "catchUpBacklog=$catchUpBacklog " +
                        "timeout=$timeout (asked ${params.timeout}) afterPause=${params.afterPause}"
        )
        val requestedAt = clock.epochMillis()
        val slidingResponse = if (isInitialSync) {
            reportSubtask(initialSyncReporter, InitialSyncStep.ServerComputing, WAIT_PROGRESS_STEPS, 0.3f) {
                whileWaitingForServer { executeSync(mode, pos, timeout, params.presence) }
            }
        } else {
            executeSync(mode, pos, timeout, params.presence)
        }
        logResponse(mode, slidingResponse, clock.epochMillis() - requestedAt)
        // The scope or open rooms may have changed while this request was in flight.
        val scope = settingsStorage.getRoomSyncScope(userId)
        val subscriptions = roomSubscriptions.snapshot()
        val directRooms = slidingResponse.extensions?.accountData?.global
                ?.lastOrNull { it.type == UserAccountDataTypes.TYPE_DIRECT_MESSAGES }
                ?.content?.values?.flatMap { (it as? List<*>)?.filterIsInstance<String>().orEmpty() }?.toSet()
        val roomTags = slidingResponse.extensions?.accountData?.rooms?.mapValues { (_, events) ->
            (events.lastOrNull { it.type == RoomAccountDataTypes.EVENT_TYPE_TAG && it.content?.get("tags") is Map<*, *> }
                    ?.content?.get("tags") as? Map<*, *>)?.keys?.filterIsInstance<String>()
        }
        fun syncedByLists(roomId: String) = isSyncedByLists(
                roomId = roomId,
                scope = scope,
                tags = roomTags?.get(roomId),
                isDirect = slidingResponse.rooms?.get(roomId)?.isDm ?: directRooms?.contains(roomId),
        )
        val openRooms = subscriptions.filterValues { it == SlidingSyncRoomSubscriptions.Depth.OPEN }.keys
        // A room closed and reopened between two responses went out unsubscribed in between, and Synapse moved
        // past whatever arrived meanwhile.
        caughtUpOpenRooms.retainAll(openRooms intersect requestedOpenRooms)
        // Before translating, which un-tracks whatever this response delivers messages for. Stored tags and DM
        // flags are still the old ones here: the response carrying a change is what writes it.
        val changedRooms = roomTags?.keys.orEmpty() + (directRooms.orEmpty().takeIf { scope == RoomSyncScope.DIRECT_MESSAGES }.orEmpty())
        val joiningSync = changedRooms.filter { it !in openRooms && syncedByLists(it) && !isSyncedByLists(it, scope) }
        if (joiningSync.isNotEmpty()) {
            Timber.i("SYNCDBG ${joiningSync.size} rooms join sync through a tag or DM change: $joiningSync")
            unsyncedRoomTracker.markMissed(joiningSync)
            failedCatchUps.removeAll(joiningSync.toSet())
        }
        // Read before translating: a tracked room delivered with messages comes out of it un-tracked and split off
        // behind a gap, which in an open room leaves the reader on a lone new message.
        val trackedBeforeTranslation = unsyncedRoomTracker.trackedRoomIds()
        val translated = MatrixPerf.time("resp.translate") {
            translator.toSyncResponse(
                    response = slidingResponse,
                    unsyncedRooms = unsyncedRoomTracker,
                    recordUnsynced = mode == SlidingSyncMode.SIMPLIFIED,
                    now = clock.epochMillis(),
                    canSyncTimeline = { roomId -> roomId in openRooms || syncedByLists(roomId) },
            )
        }
        val reopened = requestedOpenRooms.filter {
            it in openRooms && it !in caughtUpOpenRooms && (!syncedByLists(it) || it in trackedBeforeTranslation)
        }
        // A fresh connection delivers every room anyway, which un-tracks the ones it syncs.
        val backlog = if (isInitialSync || coverageIncomplete) {
            emptyList()
        } else {
            val synced = syncedTrackedRooms(scope, changedRooms, ::syncedByLists) - openRooms - failedCatchUps
            (joiningSync.filter { it in synced } + synced).distinct()
        }
        val catchUp = catchUpRooms(
                response = slidingResponse,
                syncResponse = translated,
                reopened = reopened,
                joiningSync = backlog.take(CATCH_UP_BATCH),
        )
        val syncResponse = catchUp.syncResponse
        logOpenRooms(slidingResponse, syncResponse, openRooms, scope)
        syncResponse.accountData?.list.orEmpty().firstOrNull { it.type == "m.push_rules" }?.let { event ->
            val mention = (((event.content["global"] as? Map<*, *>)?.get("override") as? List<*>)
                    ?.firstOrNull { (it as? Map<*, *>)?.get("rule_id") == ".m.rule.is_user_mention" } as? Map<*, *>)
                    ?.get("enabled")
            DebugLog.i { "NOTIFDBG sliding sync carried m.push_rules for $userId: is_user_mention enabled=$mention " +
                            "pos=$pos -> ${slidingResponse.pos} initial=$isInitialSync coverageIncomplete=$coverageIncomplete" }
        }
        // An expired connection is dropped inside executeSync, which clears the stored pos; re-reading it is
        // how this call learns the response is a fresh connection and not a delta.
        val fromToken = syncTokenStore.getSlidingSyncPos()

        if (coverageIncomplete) {
            // A catch-up pass carries rooms nobody is waiting for and holds the write dispatcher for about
            // a second, so let whatever the user just did land first.
            MatrixPerf.timeSuspending("resp.awaitDbTurn") { sessionDbPriority.awaitTurn() }
        }
        if (isInitialSync || coverageIncomplete) shieldSummaryUpdater.holdRefreshes()

        val importStartedAt = clock.epochMillis()
        val owesSpaceValidation = try {
            syncImportState.importing {
                reportSubtask(
                        reporter = initialSyncReporter,
                        initialSyncStep = InitialSyncStep.ImportingAccount,
                        totalProgress = 1,
                        parentWeight = 0.7f
                ) {
                    syncResponseHandler.handleResponse(
                            syncResponse = syncResponse,
                            fromToken = fromToken,
                            afterPause = params.afterPause,
                            reporter = initialSyncReporter,
                            // The pos is not a v2 since-token; storing it in that slot would corrupt a later fallback
                            // to sync v2. It is persisted separately below.
                            persistToken = false,
                            // Revalidating the space graph costs ~2s and every response of a fill brings new rooms,
                            // so doing it each time would spend most of a first sync on it. Run it once the fill
                            // settles.
                            deferSpaceValidation = true,
                    )
                }
            }
        } catch (failure: Throwable) {
            // The release below is never reached otherwise, and a hold outliving this response leaves every
            // room's shield stale for the session.
            shieldSummaryUpdater.releaseRefreshes()
            Timber.w(failure, "SYNCDBG import of pos=${slidingResponse.pos} failed after ${clock.epochMillis() - importStartedAt}ms, it will be replayed")
            throw failure
        }

        // Before the pos: a crash in between replays this response, which re-records the same rooms.
        unsyncedRoomTracker.commit()
        syncTokenStore.setSlidingSyncPos(slidingResponse.pos)
        // Only once imported: a response cancelled before that is replayed, and has to catch these up again.
        caughtUpOpenRooms.addAll(catchUp.caughtUpOpen)
        failedCatchUps.addAll(catchUp.failed)
        catchUpBacklog = backlog.size > CATCH_UP_BATCH
        Timber.i(
                "SYNCDBG imported pos=$fromToken -> ${slidingResponse.pos} in ${clock.epochMillis() - importStartedAt}ms " +
                        "join=${syncResponse.rooms?.join?.size} invite=${syncResponse.rooms?.invite?.size} leave=${syncResponse.rooms?.leave?.size} " +
                        "importedTimelines=${syncResponse.rooms?.join?.count { !it.value.timeline?.events.isNullOrEmpty() }}"
        )
        slidingResponse.extensions?.toDevice?.nextBatch?.let { syncTokenStore.setSlidingSyncToDeviceSince(it) }

        if (isInitialSync) {
            // The point of this transport is that the account does not have to arrive all at once. The first
            // window is enough to open the app on, so release the progress screen here and let the remaining
            // rooms land underneath the user rather than in front of them.
            syncRequestStateTracker.endAll()
            // Right after the app becomes usable rather than before it: the sidebar shows our own name and
            // avatar from here, so waiting for the whole account to arrive leaves it blank.
            val user = tryOrNull { session.profileService().getProfileAsUser(userId) }
            userStore.createOrUpdate(userId = userId, displayName = user?.displayName, avatarUrl = user?.avatarUrl)
        }

        // Rooms are summarized before m.direct is stored, so reclassify first deliveries when it changes.
        val directsChanged = syncResponse.accountData?.list.orEmpty()
                .any { it.type == UserAccountDataTypes.TYPE_DIRECT_MESSAGES }
        val initialRoomIds = syncResponse.rooms?.join?.filterValues { it.isInitialDelivery }?.keys
        if (directsChanged && !initialRoomIds.isNullOrEmpty()) {
            MatrixPerf.timeSuspending("resp.refreshDirect") { syncResponseHandler.refreshDirectChatRooms(initialRoomIds) }
        }

        spaceValidationOwed = spaceValidationOwed || owesSpaceValidation
        coverageIncomplete = advanceCoverage(mode, slidingResponse)
        syncImportState.catchUpPending = coverageIncomplete
        if (coverageIncomplete) Timber.i("SYNCDBG coverage incomplete, widened to 0..$listRangeEnd; next request skips the long poll")
        if (!coverageIncomplete) {
            shieldSummaryUpdater.releaseRefreshes()
            if (!isInitialSync) {
                syncRequestStateTracker.setSyncRequestState(SyncRequestState.IncrementalSyncDone)
            }
        }
        if (spaceValidationOwed && !coverageIncomplete) {
            spaceValidationOwed = false
            syncResponseHandler.validateSpaceHierarchy()
        }
        return syncResponse
    }

    // One opaque call, so there is no real sub-progress to report — but a bar frozen at zero for the
    // longest phase of a first sync reads as a hang. Creep towards, never reaching, the end of the phase.
    private suspend fun <T> whileWaitingForServer(block: suspend () -> T): T = coroutineScope {
        val ticker = launch {
            var elapsedMs = 0L
            while (isActive) {
                delay(WAIT_PROGRESS_TICK_MS)
                elapsedMs += WAIT_PROGRESS_TICK_MS
                val ratio = 1f - exp(-elapsedMs.toFloat() / WAIT_PROGRESS_TIME_CONSTANT_MS)
                syncRequestStateTracker.reportProgress(ratio * WAIT_PROGRESS_STEPS)
            }
        }
        try {
            block()
        } finally {
            ticker.cancel()
        }
    }

    /**
     * Synapse counts every room in a list range as delivered on each response, even one a `timeline_limit` 0
     * list gave it nothing to send for, and only records a lowered limit on a limited response. A room left
     * out of sync is therefore neither re-sent from where it stopped nor seen as asking for more when it is
     * opened again or a list starts syncing it, and what arrived in between never comes. Fetch its newest
     * page instead: once per open for [reopened], once for [joiningSync]. The handler joins the page onto the
     * stored range where they overlap and leaves a gap where not.
     */
    private suspend fun catchUpRooms(
            response: SlidingSyncResponse,
            syncResponse: SyncResponse,
            reopened: List<String>,
            joiningSync: List<String>,
    ): CatchUpResult {
        val rooms = syncResponse.rooms
        if (rooms == null || (reopened.isEmpty() && joiningSync.isEmpty())) return CatchUpResult(syncResponse)
        val join = LinkedHashMap(rooms.join)
        suspend fun catchUp(roomId: String, limit: Int, reason: String): Boolean {
            val delivered = response.rooms?.get(roomId)
            if (delivered?.initial == true) {
                Timber.i("SYNCDBG catch-up $roomId ($reason): skipped, delivered as initial with ${delivered.timeline.orEmpty().size} events")
                return true
            }
            val joined = roomId in join || (roomId !in rooms.leave && roomId !in rooms.invite && roomId !in rooms.knock &&
                    stores.room.get(roomId)?.membership == Membership.JOIN)
            if (!joined) {
                Timber.i("SYNCDBG catch-up $roomId ($reason): skipped, not joined")
                unsyncedRoomTracker.onDeliveredWithTimeline(roomId)
                return true
            }
            val startedAt = clock.epochMillis()
            val timeline = try {
                latestRoomTimelineFetcher.fetch(roomId, limit)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                Timber.w(failure, "SYNCDBG catch-up $roomId ($reason): fetch failed")
                return false
            }
            val wasTracked = unsyncedRoomTracker.onDeliveredWithTimeline(roomId)
            val deliveredTimeline = join[roomId]?.timeline
            join[roomId] = (join[roomId] ?: RoomSync()).copy(
                    timeline = timeline,
                    // The page reaches back to messages long seen or already notified.
                    notifiableEventIds = deliveredTimeline?.events.orEmpty().mapNotNullTo(HashSet()) { it.eventId },
            )
            Timber.i(
                    "SYNCDBG catch-up $roomId ($reason): fetched ${timeline.events.orEmpty().size} events in ${clock.epochMillis() - startedAt}ms " +
                            "newest=${timeline.events?.lastOrNull()?.eventId} hasPrev=${timeline.prevToken != null} " +
                            "replacedDelivered=${deliveredTimeline?.events?.size}(limited=${deliveredTimeline?.limited}) wasTracked=$wasTracked"
            )
            return true
        }
        val caughtUpOpen = reopened.filter { catchUp(it, OPEN_ROOM_TIMELINE_LIMIT, "opened") }
        val failed = (joiningSync - reopened.toSet()).filterNot { catchUp(it, INCREMENTAL_TIMELINE_LIMIT, "joined sync") }
        return CatchUpResult(syncResponse.copy(rooms = rooms.copy(join = join)), caughtUpOpen, failed)
    }

    private class CatchUpResult(
            val syncResponse: SyncResponse,
            val caughtUpOpen: List<String> = emptyList(),
            val failed: List<String> = emptyList(),
    )

    /** A widened scope starts syncing rooms that may have missed messages without ever being delivered empty. */
    private fun markRoomsJoiningSyncByScope() {
        val scope = settingsStorage.getRoomSyncScope(userId)
        val previous = RoomSyncScope.fromValue(syncTokenStore.getSlidingSyncScope())
        if (previous == scope) return
        if (previous != null) {
            val joined = stores.roomSummary.getRoomIdsByMembership(Membership.JOIN)
            val open = roomSubscriptions.snapshot().filterValues { it == SlidingSyncRoomSubscriptions.Depth.OPEN }.keys
            val joining = storedSyncedByLists(joined, scope) - storedSyncedByLists(joined, previous) - open
            Timber.i("SYNCDBG scope ${previous.value} -> ${scope.value}: ${joining.size} rooms join sync")
            unsyncedRoomTracker.markMissed(joining)
            failedCatchUps.removeAll(joining)
        }
        syncTokenStore.setSlidingSyncScope(scope.value)
    }

    /**
     * Tracked rooms the lists now sync. [changed] rooms have tags or a DM flag in this response that the store
     * does not hold yet, so [syncedByLists] judges those from the response.
     */
    private fun syncedTrackedRooms(scope: RoomSyncScope, changed: Set<String>, syncedByLists: (String) -> Boolean): Set<String> {
        val tracked = unsyncedRoomTracker.trackedRoomIds()
        if (tracked.isEmpty()) return emptySet()
        return storedSyncedByLists(tracked - changed, scope) + tracked.filter { it in changed && syncedByLists(it) }
    }

    /** [isSyncedByLists] over stored tags and DM flags, for many rooms at once. */
    private fun storedSyncedByLists(roomIds: Collection<String>, scope: RoomSyncScope): Set<String> {
        if (roomIds.isEmpty()) return emptySet()
        val whenOpen = stores.roomTag.getRoomIdsByTag(RoomTag.ROOM_TAG_SYNC_WHEN_OPEN).toSet()
        val always = stores.roomTag.getRoomIdsByTag(RoomTag.ROOM_TAG_SYNC_ALWAYS).toSet()
        val direct = if (scope == RoomSyncScope.DIRECT_MESSAGES) stores.roomSummary.directRooms().mapTo(HashSet()) { it.first } else emptySet()
        return roomIds.filterTo(HashSet()) { roomId ->
            roomId !in whenOpen && (scope == RoomSyncScope.ALL || roomId in always || roomId in direct)
        }
    }

    private fun logOpenRooms(response: SlidingSyncResponse, syncResponse: SyncResponse, openRooms: Set<String>, scope: RoomSyncScope) {
        if (openRooms.isEmpty()) return
        openRooms.forEach { roomId ->
            val received = response.rooms?.get(roomId)
            val imported = syncResponse.rooms?.join?.get(roomId)
            Timber.i(
                    "SYNCDBG open $roomId scope=${scope.value} requested=${roomId in requestedOpenRooms} caughtUp=${roomId in caughtUpOpenRooms} " +
                            "received=${received?.let { "timeline=${it.timeline?.size} limited=${it.limited} hasPrev=${it.prevBatch != null} " +
                                    "initial=${it.initial} bump=${it.bumpStamp}" } ?: "nothing"} " +
                            "imported=${imported?.timeline?.let { "timeline=${it.events?.size} limited=${it.limited}" } ?: "no timeline"} " +
                            "tracked=${unsyncedRoomTracker.isTracked(roomId)}"
            )
        }
    }

    private fun logResponse(mode: SlidingSyncMode, response: SlidingSyncResponse, durationMs: Long) {
        val rooms = response.rooms.orEmpty()
        Timber.tag(loggerTag.value).d(
                "$mode in ${durationMs}ms: rooms=${rooms.size} initial=${rooms.values.count { it.initial }} " +
                        "state=${rooms.values.sumOf { it.requiredState.orEmpty().size }} " +
                        "timeline=${rooms.values.sumOf { it.timeline.orEmpty().size }} " +
                        "toDevice=${response.extensions?.toDevice?.events.orEmpty().size} " +
                        "coverage=0..$listRangeEnd of ${response.lists?.get(ALL_ROOMS_LIST)?.count} " +
                        "limit=$FIRST_DELIVERY_TIMELINE_LIMIT pending=${response.pending}"
        )
        val withTimeline = rooms.filterValues { !it.timeline.isNullOrEmpty() }
        Timber.i(
                "SYNCDBG response $mode pos=${response.pos} in ${durationMs}ms rooms=${rooms.size} " +
                        "withTimeline=${withTimeline.size}${withTimeline.keys.take(LOG_ROOM_IDS)} " +
                        "withoutTimeline=${rooms.size - withTimeline.size} initial=${rooms.values.count { it.initial }} " +
                        "lists=${response.lists?.mapValues { it.value.count }} pending=${response.pending} " +
                        "receipts=${response.extensions?.receipts?.rooms?.size} typing=${response.extensions?.typing?.rooms?.size} " +
                        "accountDataRooms=${response.extensions?.accountData?.rooms?.keys?.take(LOG_ROOM_IDS)} " +
                        "toDevice=${response.extensions?.toDevice?.events.orEmpty().size}"
        )
    }

    private fun logRequest(mode: SlidingSyncMode, pos: String?, timeout: Long, request: SlidingSyncRequest) {
        Timber.i(
                "SYNCDBG request $mode pos=$pos timeout=$timeout scope=${settingsStorage.getRoomSyncScope(userId).value} " +
                        "lists=${request.lists?.map { (name, list) ->
                            "$name${list.ranges}/t${list.timelineLimit}" +
                                    (list.filters?.tags?.let { " tags=$it" } ?: "") +
                                    (list.filters?.notTags?.let { " notTags=$it" } ?: "") +
                                    (list.filters?.isDm?.let { " dm=$it" } ?: "")
                        }} " +
                        "subs=${request.roomSubscriptions?.map { (roomId, sub) -> "$roomId/t${sub.timelineLimit}" }} " +
                        "open=$requestedOpenRooms caughtUp=$caughtUpOpenRooms receiptLists=${request.extensions?.receipts?.lists}"
        )
    }

    /** Widens the window if rooms remain undelivered, returning whether another pass is owed. */
    private fun advanceCoverage(mode: SlidingSyncMode, response: SlidingSyncResponse): Boolean {
        return when (mode) {
            SlidingSyncMode.PAGINATED -> (response.pending ?: 0) > 0
            SlidingSyncMode.SIMPLIFIED -> {
                // Under RoomSyncScope.ALL the rooms set to sync only while open are carved out of the main list
                // into their own, and both have to be covered.
                val count = maxOf(response.lists?.get(ALL_ROOMS_LIST)?.count ?: 0, response.lists?.get(WHEN_OPEN_LIST)?.count ?: 0)
                if (count <= listRangeEnd + 1) {
                    return false
                }
                // Large coverage steps amortize network, crypto and transaction overhead.
                listRangeEnd = minOf(listRangeEnd + RANGE_STEP, count - 1)
                syncTokenStore.setSlidingSyncCoverage(listRangeEnd)
                true
            }
        }
    }

    private suspend fun executeSync(
            mode: SlidingSyncMode,
            pos: String?,
            timeout: Long,
            presence: SyncPresence?,
    ): SlidingSyncResponse {
        return try {
            requestSync(mode, pos, timeout, presence)
        } catch (cancellation: CancellationException) {
            Timber.i("SYNCDBG request cancelled: ${cancellation.message}")
            syncRequestStateTracker.setSyncRequestState(SyncRequestState.IncrementalSyncIdle)
            throw cancellation
        } catch (throwable: Throwable) {
            if (pos != null && throwable.isBadRequest()) {
                // MSC4186 expires connections with M_UNKNOWN_POS; MSC4525 drops the error and just treats
                // an unknown pos as absent. Restarting from scratch satisfies both. Synapse does not
                // reliably set the errcode, so any rejection of a request carrying a pos is read as expiry.
                Timber.w(throwable, "SYNCDBG connection expired (pos=$pos rejected), restarting from scratch")
                syncTokenStore.setSlidingSyncPos(null)
                syncTokenStore.setSlidingSyncToDeviceSince(null)
                listRangeEnd = INITIAL_RANGE_SIZE - 1
                return requestSync(mode, null, 0L, presence)
            }
            Timber.e(throwable, "SYNCDBG request error (pos=$pos timeout=$timeout)")
            syncRequestStateTracker.setSyncRequestState(SyncRequestState.IncrementalSyncError)
            throw throwable
        }
    }

    private suspend fun requestSync(
            mode: SlidingSyncMode,
            pos: String?,
            timeout: Long,
            presence: SyncPresence?,
    ): SlidingSyncResponse {
        val body = buildRequest(mode, presence)
        logRequest(mode, pos, timeout, body)
        val readTimeOut = (timeout + TIMEOUT_MARGIN).coerceAtLeast(TimeOutInterceptor.DEFAULT_LONG_TIMEOUT)
        return executeRequest(globalErrorReceiver) {
            when (mode) {
                SlidingSyncMode.SIMPLIFIED -> slidingSyncAPI.simplifiedSlidingSync(pos, timeout, body, readTimeOut = readTimeOut)
                SlidingSyncMode.PAGINATED -> slidingSyncAPI.paginatedSync(pos, timeout, body, readTimeOut = readTimeOut)
            }
        }
    }

    private fun buildRequest(mode: SlidingSyncMode, presence: SyncPresence?): SlidingSyncRequest {
        val profiles = ProfilesExtensionRequest(fields = ProfileKeys.SYNCED_EXTENDED_FIELDS)
                .takeIf { homeServerCapabilitiesDataSource.getHomeServerCapabilities()?.canUseSlidingSyncProfiles == true }
        val extensions = SlidingSyncExtensionsRequest(
                toDevice = ToDeviceExtensionRequest(since = syncTokenStore.getSlidingSyncToDeviceSince()),
                e2ee = EnabledExtensionRequest(),
                accountData = EnabledExtensionRequest(),
                receipts = RoomScopedExtensionRequest(),
                typing = RoomScopedExtensionRequest(),
                unstableProfiles = profiles,
        )
        val subscriptions = roomSubscriptions.snapshot()
        requestedOpenRooms = subscriptions.filterValues { it == SlidingSyncRoomSubscriptions.Depth.OPEN }.keys
        return when (mode) {
            SlidingSyncMode.SIMPLIFIED -> {
                val scope = settingsStorage.getRoomSyncScope(userId)
                val lists = simplifiedLists(scope)
                // Receipts and typing in rooms we hold no messages for are only traffic.
                val syncedLists = lists.filterValues { it.timelineLimit > 0 }.keys.toList()
                        .takeIf { it.size < lists.size }
                SlidingSyncRequest(
                        lists = lists,
                        roomSubscriptions = subscriptions
                                // The room-list viewport is a prefetch of messages; rooms left out are not prefetched.
                                .filter { (roomId, depth) -> depth == SlidingSyncRoomSubscriptions.Depth.OPEN || isSyncedByLists(roomId, scope) }
                                .mapValues { (_, depth) ->
                                    SlidingSyncRoomSubscription(
                                            requiredState = SlidingSyncRequiredState.EVENTS,
                                            timelineLimit = when (depth) {
                                                SlidingSyncRoomSubscriptions.Depth.OPEN -> OPEN_ROOM_TIMELINE_LIMIT
                                                SlidingSyncRoomSubscriptions.Depth.VISIBLE -> INCREMENTAL_TIMELINE_LIMIT
                                            },
                                    )
                                }
                                .takeIf { it.isNotEmpty() },
                        extensions = if (syncedLists == null) extensions else extensions.copy(
                                receipts = RoomScopedExtensionRequest(lists = syncedLists, rooms = ALL_SUBSCRIPTIONS),
                                typing = RoomScopedExtensionRequest(lists = syncedLists, rooms = ALL_SUBSCRIPTIONS),
                        ),
                        setPresence = presence?.value,
                )
            }
            // MSC4525 splits the two, so ongoing updates keep the larger limit.
            SlidingSyncMode.PAGINATED -> SlidingSyncRequest(
                    requiredState = SlidingSyncRequiredState.EVENTS,
                    pageSize = PAGE_SIZE,
                    limit = TIMELINE_LIMIT,
                    history = FIRST_DELIVERY_TIMELINE_LIMIT,
                    extensions = extensions,
                    setPresence = presence?.value,
            )
        }
    }

    /**
     * The server orders by recent activity, so a single list hands over the busiest rooms first and leaves
     * DMs and invites until whenever their turn comes. The favourites, DM and invite lists are priority
     * requests for what the user looks for first; the room list itself does not care which list a room
     * arrived on.
     *
     * A room the [scope] leaves out is still covered, with `timeline_limit` 0: its name, avatar, state and
     * bump stamp keep coming, its messages do not. The server gives a room in several lists the highest
     * limit among them, so every list that does carry messages excludes [RoomTag.ROOM_TAG_SYNC_WHEN_OPEN].
     */
    private fun simplifiedLists(scope: RoomSyncScope): Map<String, SlidingSyncListRequest> {
        val fullRange = listOf(0, listRangeEnd)
        val priorityRange = listOf(0, PRIORITY_LIST_SIZE - 1)
        val whenOpen = listOf(RoomTag.ROOM_TAG_SYNC_WHEN_OPEN)
        val always = listOf(RoomTag.ROOM_TAG_SYNC_ALWAYS)
        // Only once a room is known to carry the tag, so an unrestricted account sends the plain request.
        val carvesOutRooms = scope != RoomSyncScope.ALL || stores.roomTag.getRoomIdsByTag(RoomTag.ROOM_TAG_SYNC_WHEN_OPEN).isNotEmpty()
        fun list(range: List<Int>, synced: Boolean, filters: SlidingSyncFilters?) = SlidingSyncListRequest(
                ranges = listOf(range),
                requiredState = SlidingSyncRequiredState.EVENTS,
                timelineLimit = if (synced) FIRST_DELIVERY_TIMELINE_LIMIT else 0,
                filters = if (synced && carvesOutRooms) (filters ?: SlidingSyncFilters()).copy(notTags = whenOpen) else filters,
        )
        return when (scope) {
            RoomSyncScope.ALL -> buildMap {
                put(FAVOURITES_LIST, list(priorityRange, true, SlidingSyncFilters(tags = listOf(RoomTag.ROOM_TAG_FAVOURITE))))
                put(DM_LIST, list(priorityRange, true, SlidingSyncFilters(isDm = true)))
                put(INVITES_LIST, list(priorityRange, true, SlidingSyncFilters(isInvite = true)))
                put(ALL_ROOMS_LIST, list(fullRange, true, null))
                if (carvesOutRooms) put(WHEN_OPEN_LIST, list(fullRange, false, SlidingSyncFilters(tags = whenOpen)))
            }
            RoomSyncScope.DIRECT_MESSAGES -> mapOf(
                    FAVOURITES_LIST to list(priorityRange, false, SlidingSyncFilters(tags = listOf(RoomTag.ROOM_TAG_FAVOURITE))),
                    // Every DM rather than the first few: here this list is what carries their messages.
                    DM_LIST to list(fullRange, true, SlidingSyncFilters(isDm = true)),
                    INVITES_LIST to list(priorityRange, false, SlidingSyncFilters(isInvite = true)),
                    ALWAYS_LIST to list(fullRange, true, SlidingSyncFilters(tags = always)),
                    ALL_ROOMS_LIST to list(fullRange, false, null),
            )
            RoomSyncScope.OPENED_ONLY -> mapOf(
                    FAVOURITES_LIST to list(priorityRange, false, SlidingSyncFilters(tags = listOf(RoomTag.ROOM_TAG_FAVOURITE))),
                    DM_LIST to list(priorityRange, false, SlidingSyncFilters(isDm = true)),
                    INVITES_LIST to list(priorityRange, false, SlidingSyncFilters(isInvite = true)),
                    ALWAYS_LIST to list(fullRange, true, SlidingSyncFilters(tags = always)),
                    ALL_ROOMS_LIST to list(fullRange, false, null),
            )
        }
    }

    /** Mirrors [simplifiedLists]. */
    private fun isSyncedByLists(
            roomId: String,
            scope: RoomSyncScope,
            tags: Collection<String>? = null,
            isDirect: Boolean? = null,
    ): Boolean {
        val effectiveTags = tags ?: stores.roomTag.getTags(roomId).map { it.tagName }
        return when {
            RoomTag.ROOM_TAG_SYNC_WHEN_OPEN in effectiveTags -> false
            scope == RoomSyncScope.ALL || RoomTag.ROOM_TAG_SYNC_ALWAYS in effectiveTags -> true
            scope == RoomSyncScope.DIRECT_MESSAGES -> isDirect ?: (stores.roomSummary.get(roomId)?.isDirect == true)
            else -> false
        }
    }

    private fun Throwable.isBadRequest(): Boolean = (this as? Failure.ServerError)?.httpCode == 400

    companion object {
        private const val ALL_ROOMS_LIST = "all"
        private const val DM_LIST = "dms"
        private const val INVITES_LIST = "invites"
        private const val FAVOURITES_LIST = "favourites"
        private const val ALWAYS_LIST = "always"
        private const val WHEN_OPEN_LIST = "when_open"
        private val ALL_SUBSCRIPTIONS = listOf("*")

        // Deliberately small. Everything in the first response is written before the progress screen goes
        // away, so each of these rooms is paid for in time the user spends waiting; they exist to put a few
        // of the right rooms on screen at once, not to cover the account.
        private const val PRIORITY_LIST_SIZE = 10

        // Fetch visible rooms first so the home screen can open before full coverage.
        private const val INITIAL_RANGE_SIZE = 10

        // Each response costs about a second before a single room is written — the round trip, the crypto
        // pass over device lists, the aggregators — so a narrow step spends most of a cold fill on that
        // fixed cost (traced: 35 responses, 33s of the 116s). The import commits in ROOM_IMPORT_BATCH_SIZE
        // chunks, so a wider step does not lengthen any single write lock.
        private const val RANGE_STEP = 30

        private const val PAGE_SIZE = 100

        private const val WAIT_PROGRESS_STEPS = 100
        private const val WAIT_PROGRESS_TICK_MS = 200L
        private const val WAIT_PROGRESS_TIME_CONSTANT_MS = 6_000f

        // What a room already known to the connection may deliver per response.
        private const val TIMELINE_LIMIT = 20

        // What a room delivers the first time it is handed over. Every event here is a row written during
        // hydration and costs several milliseconds, so this dominates how long a first sync takes. Kept
        // deep enough that the room list can usually find something previewable — a room whose newest events
        // are all joins, reactions or redactions otherwise shows a blank last message and sorts to the
        // bottom until it is opened. Opening a room back-paginates from `prev_batch` for the rest.
        private const val FIRST_DELIVERY_TIMELINE_LIMIT = 8

        // What a room the user has on screen delivers per response. Deep enough that an ordinary burst of
        // messages arrives whole instead of as a gap. Only subscribed rooms get it: raising the list's own
        // limit makes the server re-deliver that much timeline for every room in range at once (traced:
        // 408 rooms, 7904 events, a 10s response and 145s of crypto to import).
        private const val INCREMENTAL_TIMELINE_LIMIT = 20

        // The room the user is reading: enough to open on without a /messages round trip.
        private const val OPEN_ROOM_TIMELINE_LIMIT = 50
        private const val LOG_ROOM_IDS = 20

        // Rooms caught up per response after they join sync; each is one /messages round trip before the import.
        private const val CATCH_UP_BATCH = 10
        private const val CONNECTION_CONFIG_VERSION = 3

        /** Stored with the pos; a pos saved under any other version belongs to a connection that gets discarded. */
        val STATE_VERSION = "${SlidingSyncRequiredState.VERSION}:$CONNECTION_CONFIG_VERSION"

        private const val TIMEOUT_MARGIN: Long = 10_000
    }
}
