/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list.actions

import com.airbnb.mvrx.MavericksState
import im.vector.app.features.spaces.RoomTagItem

data class RoomTagViewState(
        val roomId: String,
        val roomTags: List<RoomTagItem> = emptyList(),
        val availableTags: List<RoomTagItem> = emptyList(),
) : MavericksState {
    constructor(args: RoomTagArgs) : this(roomId = args.roomId)
}
