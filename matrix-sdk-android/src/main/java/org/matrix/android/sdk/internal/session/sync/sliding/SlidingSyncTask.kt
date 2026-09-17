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
import org.matrix.android.sdk.api.session.room.model.tag.RoomTag
import org.matrix.android.sdk.api.session.sync.InitialSyncStep
import org.matrix.android.sdk.api.session.sync.SyncRequestState
import org.matrix.android.sdk.api.session.sync.model.SyncResponse
import org.matrix.android.sdk.api.util.MatrixPerf
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
        @UserId private val userId: String,
) {
    // Persist coverage per connection. Start with visible rooms, then widen in large batches.
    private var listRangeEnd = INITIAL_RANGE_SIZE - 1

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
        val stateVersion = "${SlidingSyncRequiredState.VERSION}:$CONNECTION_CONFIG_VERSION"
        if (syncTokenStore.getSlidingSyncStateVersion() != stateVersion) {
            Timber.tag(loggerTag.value).i("required_state changed, restarting the sliding sync connection")
            syncTokenStore.setSlidingSyncPos(null)
            syncTokenStore.setSlidingSyncToDeviceSince(null)
            syncTokenStore.setSlidingSyncStateVersion(stateVersion)
        }

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
        val timeout = if (coverageIncomplete) 0L else params.timeout
        val requestedAt = clock.epochMillis()
        val slidingResponse = if (isInitialSync) {
            reportSubtask(initialSyncReporter, InitialSyncStep.ServerComputing, WAIT_PROGRESS_STEPS, 0.3f) {
                whileWaitingForServer { executeSync(mode, pos, timeout, params.presence) }
            }
        } else {
            executeSync(mode, pos, timeout, params.presence)
        }
        logResponse(mode, slidingResponse, clock.epochMillis() - requestedAt)
        val syncResponse = MatrixPerf.time("resp.translate") { translator.toSyncResponse(slidingResponse) }
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
            throw failure
        }

        syncTokenStore.setSlidingSyncPos(slidingResponse.pos)
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
    }

    /** Widens the window if rooms remain undelivered, returning whether another pass is owed. */
    private fun advanceCoverage(mode: SlidingSyncMode, response: SlidingSyncResponse): Boolean {
        return when (mode) {
            SlidingSyncMode.PAGINATED -> (response.pending ?: 0) > 0
            SlidingSyncMode.SIMPLIFIED -> {
                val count = response.lists?.get(ALL_ROOMS_LIST)?.count ?: 0
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
            Timber.tag(loggerTag.value).d("Sliding sync request cancelled")
            syncRequestStateTracker.setSyncRequestState(SyncRequestState.IncrementalSyncIdle)
            throw cancellation
        } catch (throwable: Throwable) {
            if (pos != null && throwable.isBadRequest()) {
                // MSC4186 expires connections with M_UNKNOWN_POS; MSC4525 drops the error and just treats
                // an unknown pos as absent. Restarting from scratch satisfies both. Synapse does not
                // reliably set the errcode, so any rejection of a request carrying a pos is read as expiry.
                Timber.tag(loggerTag.value).w("Sliding sync connection expired, restarting from scratch")
                syncTokenStore.setSlidingSyncPos(null)
                syncTokenStore.setSlidingSyncToDeviceSince(null)
                listRangeEnd = INITIAL_RANGE_SIZE - 1
                return requestSync(mode, null, 0L, presence)
            }
            Timber.tag(loggerTag.value).e(throwable, "Sliding sync request error")
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
                receipts = EnabledExtensionRequest(),
                typing = EnabledExtensionRequest(),
                unstableProfiles = profiles,
        )
        return when (mode) {
            // The server orders by recent activity, so a single list hands over the busiest rooms first and
            // leaves DMs and invites until whenever their turn comes. These extra lists are priority
            // requests for the two the user actually looks for first; the room list itself does not care
            // which list a room arrived on.
            SlidingSyncMode.SIMPLIFIED -> SlidingSyncRequest(
                    lists = mapOf(
                            FAVOURITES_LIST to priorityList(SlidingSyncFilters(tags = listOf(RoomTag.ROOM_TAG_FAVOURITE))),
                            DM_LIST to priorityList(SlidingSyncFilters(isDm = true)),
                            INVITES_LIST to priorityList(SlidingSyncFilters(isInvite = true)),
                            ALL_ROOMS_LIST to SlidingSyncListRequest(
                                    ranges = listOf(listOf(0, listRangeEnd)),
                                    requiredState = SlidingSyncRequiredState.EVENTS,
                                    timelineLimit = FIRST_DELIVERY_TIMELINE_LIMIT,
                            )
                    ),
                    roomSubscriptions = roomSubscriptions.snapshot()
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
                    extensions = extensions,
                    setPresence = presence?.value,
            )
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

    /** A narrow, fixed window: these lists exist to surface their rooms early, not to cover them all. */
    private fun priorityList(filters: SlidingSyncFilters) = SlidingSyncListRequest(
            ranges = listOf(listOf(0, PRIORITY_LIST_SIZE - 1)),
            requiredState = SlidingSyncRequiredState.EVENTS,
            timelineLimit = FIRST_DELIVERY_TIMELINE_LIMIT,
            filters = filters,
    )

    private fun Throwable.isBadRequest(): Boolean = (this as? Failure.ServerError)?.httpCode == 400

    companion object {
        private const val ALL_ROOMS_LIST = "all"
        private const val DM_LIST = "dms"
        private const val INVITES_LIST = "invites"
        private const val FAVOURITES_LIST = "favourites"

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
        private const val CONNECTION_CONFIG_VERSION = 3

        private const val TIMEOUT_MARGIN: Long = 10_000
    }
}
