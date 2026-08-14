/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.sliding

import kotlinx.coroutines.CancellationException
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.model.tag.RoomTag
import org.matrix.android.sdk.api.session.sync.InitialSyncStep
import org.matrix.android.sdk.api.session.sync.SyncRequestState
import org.matrix.android.sdk.api.session.sync.model.SyncResponse
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.TimeOutInterceptor
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.sync.SyncPresence
import org.matrix.android.sdk.internal.session.sync.SyncRequestStateTracker
import org.matrix.android.sdk.internal.session.sync.SyncResponseHandler
import org.matrix.android.sdk.internal.session.sync.SyncTask
import org.matrix.android.sdk.internal.session.sync.SyncTokenStore
import org.matrix.android.sdk.internal.session.user.UserStore
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber
import javax.inject.Inject

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
        @UserId private val userId: String,
) {

    // How far MSC4186's single list currently reaches. It starts narrow so the room list paints fast and
    // widens a step per response until it spans every room. Persisted with the connection: re-narrowing it
    // on every launch would spend a round trip per step walking back up to a coverage the server already
    // has, for rooms that are all long since stored.
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
        val stateVersion = SlidingSyncRequiredState.VERSION
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

        // One response per call, rather than looping until the account is covered: the sync thread treats
        // this method returning as "the sync finished", and holding it open for the whole fill leaves the
        // spinner up, the thread unable to pause when the app is backgrounded, and every other database
        // user queued behind an unbroken run of writes. Coverage continues on the next call instead.
        val timeout = if (coverageIncomplete) 0L else params.timeout
        val requestedAt = clock.epochMillis()
        val slidingResponse = executeSync(mode, pos, timeout, params.presence)
        logResponse(mode, slidingResponse, clock.epochMillis() - requestedAt)
        val syncResponse = translator.toSyncResponse(slidingResponse)
        // An expired connection is dropped inside executeSync, which clears the stored pos; re-reading it is
        // how this call learns the response is a fresh connection and not a delta.
        val fromToken = syncTokenStore.getSlidingSyncPos()

        val owesSpaceValidation = syncResponseHandler.handleResponse(
                syncResponse = syncResponse,
                fromToken = fromToken,
                afterPause = params.afterPause,
                reporter = if (isInitialSync) syncRequestStateTracker else null,
                // The pos is not a v2 since-token; storing it in that slot would corrupt a later fallback to
                // sync v2. It is persisted separately below.
                persistToken = false,
                // Revalidating the space graph costs ~2s and every response of a fill brings new rooms, so
                // doing it each time would spend most of a first sync on it. Run it once the fill settles.
                deferSpaceValidation = true,
        )

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
        } else {
            syncRequestStateTracker.setSyncRequestState(SyncRequestState.IncrementalSyncDone)
        }

        if (syncResponse.rooms?.join?.values?.any { it.isInitialDelivery } == true) {
            // Account data arrives with the first response but rooms keep coming after it, so rooms handed
            // over later were not in the database when m.direct was applied and would never be seen as DMs.
            syncResponseHandler.refreshDirectChatRooms()
        }

        spaceValidationOwed = spaceValidationOwed || owesSpaceValidation
        coverageIncomplete = advanceCoverage(mode, slidingResponse)
        if (spaceValidationOwed && !coverageIncomplete) {
            spaceValidationOwed = false
            syncResponseHandler.validateSpaceHierarchy()
        }
        return syncResponse
    }

    private fun logResponse(mode: SlidingSyncMode, response: SlidingSyncResponse, durationMs: Long) {
        val rooms = response.rooms.orEmpty()
        Timber.tag(loggerTag.value).d(
                "$mode in ${durationMs}ms: rooms=${rooms.size} initial=${rooms.values.count { it.initial }} " +
                        "state=${rooms.values.sumOf { it.requiredState.orEmpty().size }} " +
                        "timeline=${rooms.values.sumOf { it.timeline.orEmpty().size }} " +
                        "toDevice=${response.extensions?.toDevice?.events.orEmpty().size} " +
                        "coverage=0..$listRangeEnd of ${response.lists?.get(ALL_ROOMS_LIST)?.count} pending=${response.pending}"
        )
    }

    /** Widens the window if rooms remain undelivered, returning whether another pass is owed. */
    private fun advanceCoverage(mode: SlidingSyncMode, response: SlidingSyncResponse): Boolean {
        return when (mode) {
            SlidingSyncMode.PAGINATED -> (response.pending ?: 0) > 0
            SlidingSyncMode.SIMPLIFIED -> {
                val count = response.lists?.get(ALL_ROOMS_LIST)?.count ?: 0
                if (count <= listRangeEnd + 1) return false
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
        val extensions = SlidingSyncExtensionsRequest(
                toDevice = ToDeviceExtensionRequest(since = syncTokenStore.getSlidingSyncToDeviceSince()),
                e2ee = EnabledExtensionRequest(),
                accountData = EnabledExtensionRequest(),
                receipts = EnabledExtensionRequest(),
                typing = EnabledExtensionRequest(),
        )
        return when (mode) {
            // MSC4186 has only the one limit, and it governs first delivery for every room the range
            // reaches, so it has to be the cheap one.
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

        // The first window is small so the app opens on it, and the rest arrives in modest batches after
        // the user is already looking at their rooms — each batch is a database transaction competing with
        // the UI, so widening in huge steps would trade a fast start for a janky one.
        private const val INITIAL_RANGE_SIZE = 10

        // Each pass is written in one transaction, and everything else the user does — opening a room,
        // sending a message — queues behind it. At ~90ms of database work per room, 50 rooms is a
        // five-second block on the whole app; this keeps each one near a second so the background fill
        // stays out of the way of whatever the user is actually doing.
        private const val RANGE_STEP = 12

        private const val PAGE_SIZE = 100

        // What a room already known to the connection may deliver per response.
        private const val TIMELINE_LIMIT = 20

        // What a room delivers the first time it is handed over. Every event here is a row written during
        // hydration and costs several milliseconds, so this dominates how long a first sync takes. Kept
        // deep enough that the room list can usually find something previewable — a room whose newest events
        // are all joins, reactions or redactions otherwise shows a blank last message and sorts to the
        // bottom until it is opened. Opening a room back-paginates from `prev_batch` for the rest.
        private const val FIRST_DELIVERY_TIMELINE_LIMIT = 8

        private const val TIMEOUT_MARGIN: Long = 10_000
    }
}
