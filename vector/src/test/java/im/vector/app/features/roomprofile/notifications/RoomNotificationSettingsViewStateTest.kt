/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.notifications

import com.airbnb.mvrx.Success
import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.android.sdk.api.session.room.notification.RoomNotificationState

class RoomNotificationSettingsViewStateTest {

    @Test
    fun `mentions only remains selected`() {
        val state = RoomNotificationSettingsViewState(
                roomId = "!room:example.org",
                notificationState = Success(RoomNotificationState.MENTIONS_ONLY)
        )

        assertEquals(Success(RoomNotificationState.MENTIONS_ONLY), state.notificationStateMapped)
    }

    @Test
    fun `offers the same choices for every room`() {
        val state = RoomNotificationSettingsViewState(roomId = "!room:example.org")

        assertEquals(
                listOf(
                        RoomNotificationState.ALL_MESSAGES_NOISY,
                        RoomNotificationState.MENTIONS_ONLY,
                        RoomNotificationState.MUTE
                ),
                state.notificationOptions
        )
    }
}
