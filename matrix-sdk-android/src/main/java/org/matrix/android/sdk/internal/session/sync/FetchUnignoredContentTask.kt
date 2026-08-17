/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.sync.model.InvitedRoomSync
import org.matrix.android.sdk.api.session.sync.model.RoomSync
import org.matrix.android.sdk.api.session.sync.model.RoomSyncTimeline
import org.matrix.android.sdk.api.session.sync.model.RoomsSyncResponse
import org.matrix.android.sdk.api.session.sync.model.SyncResponse
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.session.room.timeline.PaginationDirection
import org.matrix.android.sdk.internal.task.Task
import timber.log.Timber
import javax.inject.Inject

internal interface FetchUnignoredContentTask : Task<FetchUnignoredContentTask.Params, Unit> {
    data class Params(val unIgnoredUserIds: List<String>)
}

/**
 * Recovers content the homeserver hid while a user was ignored, without a destructive full re-init-sync.
 * The spec only filters ignored users against the *current* ignore list, so anything asked for now comes
 * back unfiltered — but nothing re-sends it on its own, since it sits below the sync position.
 *
 *  - Rooms shared with the un-ignored user are refetched a page at a time from `/messages`, and applied
 *    as a `limited` timeline: `SqlRoomSyncHandler.handleTimelineEvents` then clears that room's cache and
 *    rebuilds it from the server's now-unfiltered ordering (their messages were interleaved mid-range, so
 *    only a rebuild surfaces them). Scroll-up back-paginates the rest. One request per room, so the cost
 *    is the handful of rooms involved rather than the whole account.
 *  - Invites need a fresh `/sync`, being carried by membership rather than a room timeline — but only on
 *    sync v2. A sliding connection recomputes its room lists from the membership snapshot on every
 *    request (see Synapse's `sliding_sync/room_lists.py`), so it re-offers the invite by itself.
 *
 * `suppressPush` keeps the recovered backlog from notifying, and the sync token is left untouched.
 */
internal class DefaultFetchUnignoredContentTask @Inject constructor(
        private val syncAPI: SyncAPI,
        private val roomAPI: RoomAPI,
        private val syncResponseHandler: SyncResponseHandler,
        private val stores: SessionStores,
        private val globalErrorReceiver: GlobalErrorReceiver,
) : FetchUnignoredContentTask {

    override suspend fun execute(params: FetchUnignoredContentTask.Params) {
        val sharedRoomIds = stores.roomSummary.roomIdsWithActiveMembers(params.unIgnoredUserIds)
        val onSlidingSync = stores.syncToken.getSlidingSyncPos() != null
        val (invites, sharedJoins) = coroutineScope {
            val invitesAsync = async { if (onSlidingSync) emptyMap() else fetchInvites() }
            val joinsAsync = async {
                // A few at a time: someone ignored for spamming can share a lot of rooms, and this
                // should not turn into a hundred simultaneous requests.
                sharedRoomIds.chunked(PARALLEL_ROOMS)
                        .flatMap { batch -> batch.map { roomId -> async { roomId to reloadRoom(roomId) } }.awaitAll() }
                        .mapNotNull { (roomId, sync) -> sync?.let { roomId to it } }
                        .toMap()
            }
            invitesAsync.await() to joinsAsync.await()
        }
        Timber.d("Un-ignore catch-up: ${invites.size} invite(s), reloaded ${sharedJoins.size}/${sharedRoomIds.size} shared room(s)")
        if (invites.isEmpty() && sharedJoins.isEmpty()) return

        val recovered = SyncResponse(rooms = RoomsSyncResponse(invite = invites, join = sharedJoins))
        // fromToken non-null -> additive incremental semantics (recovered E2E events still decrypt inline);
        // persistToken=false keeps the real sync position; suppressPush avoids notifying for the backlog.
        syncResponseHandler.handleResponse(
                syncResponse = recovered,
                fromToken = CATCHUP_TOKEN,
                afterPause = false,
                reporter = null,
                persistToken = false,
                suppressPush = true,
        )
    }

    /** The room's newest page, oldest-first, shaped as the gappy sync the rebuild path expects. */
    private suspend fun reloadRoom(roomId: String): RoomSync? {
        val page = tryOrNull("Un-ignore catch-up failed for $roomId") {
            executeRequest(globalErrorReceiver) {
                roomAPI.getRoomMessagesFrom(roomId, from = null, dir = PaginationDirection.BACKWARDS.value, limit = PAGE_SIZE, filter = null)
            }
        } ?: return null
        val events = page.events.reversed().takeIf { it.isNotEmpty() } ?: return null
        return RoomSync(timeline = RoomSyncTimeline(events = events, limited = true, prevToken = page.end))
    }

    private suspend fun fetchInvites(): Map<String, InvitedRoomSync> {
        val requestParams = mapOf("timeout" to "0", "filter" to INVITES_FILTER)
        val response = executeRequest(globalErrorReceiver) { syncAPI.sync(params = requestParams) }
        return response.rooms?.invite.orEmpty()
    }

    companion object {
        private const val CATCHUP_TOKEN = "ignore_list_change_catchup"
        private const val PAGE_SIZE = 50
        private const val PARALLEL_ROOMS = 4

        // Invites carry their own stripped state (`unsigned.invite_room_state`), which the room filter
        // never touches, so this asks the server to build nothing else.
        private const val INVITES_FILTER =
                """{"presence":{"types":[]},"account_data":{"types":[]},"room":{"timeline":{"limit":0},""" +
                        """"state":{"types":[]},"ephemeral":{"types":[]},"account_data":{"types":[]}}}"""
    }
}
