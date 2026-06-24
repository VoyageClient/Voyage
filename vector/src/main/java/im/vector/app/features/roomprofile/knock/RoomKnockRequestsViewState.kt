/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.knock

import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MavericksState
import com.airbnb.mvrx.Uninitialized
import im.vector.app.features.roomprofile.RoomProfileArgs
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.session.room.model.RoomSummary

data class RoomKnockRequestsViewState(
        val roomId: String,
        val roomSummary: Async<RoomSummary> = Uninitialized,
        val knockRequests: Async<List<RoomMemberSummary>> = Uninitialized,
        val reasons: Map<String, String?> = emptyMap(),
        val onGoingModerationAction: List<String> = emptyList(),
        val canModerate: Boolean = false
) : MavericksState {

    constructor(args: RoomProfileArgs) : this(roomId = args.roomId)
}
