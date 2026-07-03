/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.relation

import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface FetchReactionsTask : Task<FetchReactionsTask.Params, List<Event>> {
    data class Params(
            val roomId: String,
            val eventId: String
    )
}

// The local annotations store only knows the reaction events this device actually synced (and its
// counter is derived from them), so it can undercount. Page the server's m.annotation relations to
// enumerate every reaction event id — needed to redact all reactions on a message, not just the loaded ones.
internal class DefaultFetchReactionsTask @Inject constructor(
        private val roomAPI: RoomAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
) : FetchReactionsTask {

    override suspend fun execute(params: FetchReactionsTask.Params): List<Event> {
        val reactions = ArrayList<Event>()
        var from: String? = null
        var page = 0
        do {
            val response = executeRequest(globalErrorReceiver) {
                roomAPI.getRelations(
                        roomId = params.roomId,
                        eventId = params.eventId,
                        relationType = RelationType.ANNOTATION,
                        from = from,
                        limit = PAGE_SIZE,
                )
            }
            reactions += response.chunks.filter { it.type == EventType.REACTION && !it.isRedacted() }
            from = response.nextBatch
        } while (from != null && response.chunks.isNotEmpty() && ++page < MAX_PAGES)
        return reactions
    }

    companion object {
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 50
    }
}
