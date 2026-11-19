/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.sliding

import org.matrix.android.sdk.api.session.sync.model.RoomSyncTimeline
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.filter.FilterFactory
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.session.room.timeline.PaginationDirection
import javax.inject.Inject

/** A room's newest events from `/messages`, shaped as the limited timeline a sync would have delivered. */
internal class LatestRoomTimelineFetcher @Inject constructor(
        private val roomAPI: RoomAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
) {
    private val filter by lazy { FilterFactory.createDefaultRoomFilter().toJSONString() }

    suspend fun fetch(roomId: String, limit: Int): RoomSyncTimeline {
        val response = executeRequest(globalErrorReceiver) {
            roomAPI.getRoomMessagesFrom(roomId, null, PaginationDirection.BACKWARDS.value, limit, filter)
        }
        // Limited even when it reaches the room start: a batch that does not overlap the stored live
        // range must start a new one rather than be appended after it.
        return RoomSyncTimeline(events = response.chunk.orEmpty().asReversed(), limited = true, prevToken = response.end)
    }
}
