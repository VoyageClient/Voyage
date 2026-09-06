/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.acl

import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MavericksState
import com.airbnb.mvrx.Uninitialized
import im.vector.app.features.roomprofile.RoomProfileArgs
import org.matrix.android.sdk.api.session.room.model.RoomSummary

data class RoomAclEntry(val id: Long, val server: String, val allowed: Boolean)

data class RoomAclViewState(
        val roomId: String,
        val roomSummary: Async<RoomSummary> = Uninitialized,
        val entries: List<RoomAclEntry> = emptyList(),
        val allowIpLiterals: Boolean = true,
        val canEdit: Boolean = false,
        val allowedExpanded: Boolean = true,
        val deniedExpanded: Boolean = true,
        val loading: Boolean = true,
        val saving: Boolean = false,
        val query: String = ""
) : MavericksState {
    constructor(args: RoomProfileArgs) : this(roomId = args.roomId)
}
