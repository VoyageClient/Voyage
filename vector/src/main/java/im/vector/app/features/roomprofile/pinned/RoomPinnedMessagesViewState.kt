/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.pinned

import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MavericksState
import com.airbnb.mvrx.Uninitialized
import im.vector.app.features.roomprofile.RoomProfileArgs
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent

data class RoomPinnedMessagesViewState(
        val roomId: String,
        val pinnedEvents: Async<List<TimelineEvent>> = Uninitialized,
        // Event equality ignores the transient decryption result, so a decrypt-triggered re-emission
        // can produce an == list; bump this so Mavericks still notifies and the list re-renders.
        val pinnedEventsTick: Int = 0,
        val canEditPinnedEvents: Boolean = false,
) : MavericksState {

    constructor(args: RoomProfileArgs) : this(roomId = args.roomId)
}
