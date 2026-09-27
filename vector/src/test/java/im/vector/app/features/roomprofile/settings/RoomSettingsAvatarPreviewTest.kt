/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.settings

import android.net.Uri
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomSettingsAvatarPreviewTest {

    private val saved = Uri.parse("content://avatar/saved")
    private val picked = Uri.parse("content://avatar/picked")

    @Test
    fun `saved avatar stays visible without leaving a pending change`() {
        val state = RoomSettingsViewState(roomId = "!room", savedAvatarPreviewUri = saved)

        state.avatarPreviewUri shouldBeEqualTo saved
        state.hasPendingChanges() shouldBeEqualTo false
    }

    @Test
    fun `new selection replaces the saved preview and remains visible after applying`() {
        val state = RoomSettingsViewState(
                roomId = "!room", savedAvatarPreviewUri = saved,
                avatarAction = RoomSettingsViewState.AvatarAction.UpdateAvatar(picked, "avatar.webp")
        )

        state.avatarPreviewUri shouldBeEqualTo picked
        state.hasPendingChanges() shouldBeEqualTo true
        val applied = state.copy(avatarAction = RoomSettingsViewState.AvatarAction.None, savedAvatarPreviewUri = picked)
        applied.avatarPreviewUri shouldBeEqualTo picked
        applied.hasPendingChanges() shouldBeEqualTo false
    }

    @Test
    fun `deleting a saved avatar clears its preview`() {
        val state = RoomSettingsViewState(
                roomId = "!room", savedAvatarPreviewUri = saved,
                avatarAction = RoomSettingsViewState.AvatarAction.DeleteAvatar
        )

        state.avatarPreviewUri shouldBeEqualTo null
        state.hasPendingChanges() shouldBeEqualTo true
    }
}
