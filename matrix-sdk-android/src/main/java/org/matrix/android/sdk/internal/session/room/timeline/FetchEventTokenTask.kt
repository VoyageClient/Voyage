/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.filter.FilterRepository
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

/** The pagination token on the given side of a stored event, or null if the server has none. */
internal interface FetchEventTokenTask : Task<FetchEventTokenTask.Params, String?> {
    data class Params(
            val roomId: String,
            val eventId: String,
            val direction: PaginationDirection,
    )
}

internal class DefaultFetchEventTokenTask @Inject constructor(
        private val roomAPI: RoomAPI,
        private val filterRepository: FilterRepository,
        private val globalErrorReceiver: GlobalErrorReceiver,
) : FetchEventTokenTask {

    override suspend fun execute(params: FetchEventTokenTask.Params): String? {
        val filter = filterRepository.getRoomFilterBody()
        val response = executeRequest(globalErrorReceiver) {
            roomAPI.getContextOfEvent(params.roomId, params.eventId, 0, filter)
        }
        return if (params.direction == PaginationDirection.FORWARDS) response.end else response.start
    }
}
