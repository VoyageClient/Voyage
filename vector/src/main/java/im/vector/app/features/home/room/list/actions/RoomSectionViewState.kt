/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list.actions

import com.airbnb.mvrx.MavericksState
import im.vector.app.features.home.room.list.sections.RoomSections

data class RoomSectionViewState(
        val roomId: String,
        val currentSectionTag: String? = null,
        val sections: List<RoomSections.CustomRoomSection> = emptyList(),
) : MavericksState {
    constructor(args: RoomSectionArgs) : this(roomId = args.roomId)
}
