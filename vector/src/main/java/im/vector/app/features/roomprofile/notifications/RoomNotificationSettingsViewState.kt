/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.notifications

import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MavericksState
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import im.vector.app.features.home.room.list.actions.RoomListActionsArgs
import im.vector.app.features.roomprofile.RoomProfileArgs
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.notification.RoomNotificationState

data class RoomNotificationSettingsViewState(
        val roomId: String,
        val roomSummary: Async<RoomSummary> = Uninitialized,
        val isLoading: Boolean = false,
        val notificationState: Async<RoomNotificationState> = Uninitialized
) : MavericksState {
    constructor(args: RoomProfileArgs) : this(roomId = args.roomId)
    constructor(args: RoomListActionsArgs) : this(roomId = args.roomId)
}

/**
 * Used to map this old room notification settings to the new options in v2.
 */
val RoomNotificationSettingsViewState.notificationStateMapped: Async<RoomNotificationState>
    get() {
        return when {
            notificationState() == RoomNotificationState.ALL_MESSAGES -> Success(RoomNotificationState.ALL_MESSAGES_NOISY)
            else -> notificationState
        }
    }

val RoomNotificationSettingsViewState.notificationOptions: List<RoomNotificationState>
    get() = listOf(RoomNotificationState.ALL_MESSAGES_NOISY, RoomNotificationState.MENTIONS_ONLY, RoomNotificationState.MUTE)
