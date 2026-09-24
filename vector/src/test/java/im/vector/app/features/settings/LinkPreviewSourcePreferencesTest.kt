/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import androidx.preference.PreferenceManager
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.settings.LinkPreviewSource
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val A_USER_ID = "@alice:example.org"
private const val ANOTHER_USER_ID = "@bob:example.org"
private const val A_ROOM_ID = "!room:example.org"
private const val ANOTHER_ROOM_ID = "!other:example.org"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LinkPreviewSourcePreferencesTest {

    private val context = RuntimeEnvironment.getApplication()
    private val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)

    private val vectorPreferences = VectorPreferences(
            context = context,
            clock = mockk(relaxed = true),
            buildMeta = mockk(relaxed = true),
            vectorFeatures = mockk(relaxed = true),
            defaultPrefs = defaultPrefs,
            stringProvider = mockk(relaxed = true),
    )

    @Test
    fun `out of the box, encrypted rooms preview on the device and the others on the homeserver`() {
        vectorPreferences.getLinkPreviewSource(A_USER_ID, isEncrypted = true) shouldBeEqualTo LinkPreviewSource.DEVICE
        vectorPreferences.getLinkPreviewSource(A_USER_ID, isEncrypted = false) shouldBeEqualTo LinkPreviewSource.SERVER
        vectorPreferences.getRoomLinkPreviewOverride(A_USER_ID, A_ROOM_ID).shouldBeNull()
    }

    @Test
    fun `account-wide settings do not carry over to another account`() {
        vectorPreferences.setLinkPreviewSource(A_USER_ID, isEncrypted = true, LinkPreviewSource.NONE)

        vectorPreferences.getLinkPreviewSource(A_USER_ID, isEncrypted = true) shouldBeEqualTo LinkPreviewSource.NONE
        vectorPreferences.getLinkPreviewSource(A_USER_ID, isEncrypted = false) shouldBeEqualTo LinkPreviewSource.SERVER
        vectorPreferences.getLinkPreviewSource(ANOTHER_USER_ID, isEncrypted = true) shouldBeEqualTo LinkPreviewSource.DEVICE
    }

    @Test
    fun `a room override is kept for that room and account only`() {
        vectorPreferences.setRoomLinkPreviewOverride(A_USER_ID, A_ROOM_ID, LinkPreviewSource.NONE)

        vectorPreferences.getRoomLinkPreviewOverride(A_USER_ID, A_ROOM_ID) shouldBeEqualTo LinkPreviewSource.NONE
        vectorPreferences.getRoomLinkPreviewOverride(A_USER_ID, ANOTHER_ROOM_ID).shouldBeNull()
        vectorPreferences.getRoomLinkPreviewOverride(ANOTHER_USER_ID, A_ROOM_ID).shouldBeNull()
    }

    @Test
    fun `clearing a room override puts the room back on the account-wide setting`() {
        vectorPreferences.setRoomLinkPreviewOverride(A_USER_ID, A_ROOM_ID, LinkPreviewSource.SERVER)

        vectorPreferences.setRoomLinkPreviewOverride(A_USER_ID, A_ROOM_ID, null)

        vectorPreferences.getRoomLinkPreviewOverride(A_USER_ID, A_ROOM_ID).shouldBeNull()
    }

    @Test
    fun `the settings are stored where the SDK looks for them`() {
        vectorPreferences.setLinkPreviewSource(A_USER_ID, isEncrypted = true, LinkPreviewSource.NONE)
        vectorPreferences.setLinkPreviewSource(A_USER_ID, isEncrypted = false, LinkPreviewSource.DEVICE)
        vectorPreferences.setRoomLinkPreviewOverride(A_USER_ID, A_ROOM_ID, LinkPreviewSource.SERVER)

        // The SDK reads these very keys from the same store; see DefaultLightweightSettingsStorage.
        defaultPrefs.getString("SETTINGS_LINK_PREVIEW_ENCRYPTED_KEY_$A_USER_ID", null) shouldBeEqualTo "none"
        defaultPrefs.getString("SETTINGS_LINK_PREVIEW_UNENCRYPTED_KEY_$A_USER_ID", null) shouldBeEqualTo "device"
        defaultPrefs.getString("SETTINGS_LINK_PREVIEW_SOURCE_KEY_${A_USER_ID}_$A_ROOM_ID", null) shouldBeEqualTo "server"
    }
}
