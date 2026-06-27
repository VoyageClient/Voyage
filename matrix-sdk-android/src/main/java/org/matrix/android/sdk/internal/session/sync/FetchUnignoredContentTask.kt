/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync

import org.matrix.android.sdk.api.session.sync.model.RoomsSyncResponse
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.task.Task
import timber.log.Timber
import javax.inject.Inject

internal interface FetchUnignoredContentTask : Task<FetchUnignoredContentTask.Params, Unit> {
    data class Params(val unIgnoredUserIds: List<String>)
}

/**
 * Recovers content the homeserver hid while a user was ignored, without a destructive full re-init-sync.
 *
 * The spec only filters ignored users on the "sync API" against the *current* ignore list, and names
 * "start a new sync stream" as a valid client recovery — so a fresh (no `since`) /sync, now un-ignored,
 * re-serves their content. We do one, but consume only what we want and don't advance the sync token:
 *  - ALL `invite`s are merged additively (invites from ignored users are never re-sent by an incremental
 *    sync; `handleInvitedRoom` never wipes a timeline, so this is purely additive).
 *  - `join` is kept ONLY for rooms the un-ignored user is in, and those are reloaded: a no-`since` room is
 *    `limited`, so `SqlRoomSyncHandler.handleTimelineEvents` clears that room's cache and rebuilds it from
 *    the server's now-unfiltered ordering (the during-ignore messages were interleaved mid-range, so the
 *    only way to surface them is a rebuild, not pagination). Scroll-up then back-paginates the rest.
 * Non-shared rooms are dropped untouched. `suppressPush` keeps the reloaded backlog from notifying.
 */
internal class DefaultFetchUnignoredContentTask @Inject constructor(
        private val syncAPI: SyncAPI,
        private val syncResponseHandler: SyncResponseHandler,
        private val stores: SessionStores,
        private val globalErrorReceiver: GlobalErrorReceiver,
) : FetchUnignoredContentTask {

    override suspend fun execute(params: FetchUnignoredContentTask.Params) {
        val requestParams = mapOf(
                "timeout" to "0",
                "filter" to CATCHUP_FILTER,
        )
        val response = executeRequest(globalErrorReceiver) {
            syncAPI.sync(params = requestParams)
        }

        val invites = response.rooms?.invite.orEmpty()
        val sharedRoomIds = stores.roomSummary.roomIdsWithActiveMembers(params.unIgnoredUserIds).toSet()
        val sharedJoins = response.rooms?.join.orEmpty().filterKeys { it in sharedRoomIds }
        Timber.d("Un-ignore catch-up: ${invites.size} invite(s), reloading ${sharedJoins.size} shared room(s)")
        if (invites.isEmpty() && sharedJoins.isEmpty()) return

        val trimmed = response.copy(
                rooms = RoomsSyncResponse(invite = invites, join = sharedJoins),
                accountData = null,
                presence = null,
                toDevice = null,
                deviceLists = null,
        )
        // fromToken non-null -> additive incremental semantics (recovered E2E events still decrypt inline);
        // persistToken=false keeps the real sync position; suppressPush avoids notifying for the backlog.
        syncResponseHandler.handleResponse(
                syncResponse = trimmed,
                fromToken = CATCHUP_TOKEN,
                afterPause = false,
                reporter = null,
                persistToken = false,
                suppressPush = true,
        )
    }

    companion object {
        private const val CATCHUP_TOKEN = "ignore_list_change_catchup"

        // No `since` -> full snapshot; keep a modest per-room timeline window, drop the rest.
        private const val CATCHUP_FILTER =
                """{"presence":{"types":[]},"account_data":{"types":[]},"room":{"timeline":{"limit":20},"ephemeral":{"types":[]},"account_data":{"types":[]}}}"""
    }
}
