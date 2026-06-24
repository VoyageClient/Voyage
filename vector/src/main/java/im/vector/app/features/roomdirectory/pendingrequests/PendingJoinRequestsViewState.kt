/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomdirectory.pendingrequests

import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MavericksState
import com.airbnb.mvrx.Uninitialized
import org.matrix.android.sdk.api.session.room.model.RoomSummary

data class PendingJoinRequestsViewState(
        val requests: Async<List<RoomSummary>> = Uninitialized,
        val onGoingCancellation: Set<String> = emptySet(),
        // Hidden immediately on cancel even though the local summary may still report KNOCK until a sync catches up.
        val cancelledRoomIds: Set<String> = emptySet()
) : MavericksState
