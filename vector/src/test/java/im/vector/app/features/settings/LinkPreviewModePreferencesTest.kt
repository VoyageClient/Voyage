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
import org.matrix.android.sdk.api.settings.LinkPreviewMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val A_ROOM_ID = "!room:example.org"
private const val ANOTHER_ROOM_ID = "!other:example.org"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LinkPreviewModePreferencesTest {

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
    fun `links are previewed by this device out of the box`() {
        vectorPreferences.getLinkPreviewMode() shouldBeEqualTo LinkPreviewMode.ALWAYS
        vectorPreferences.getLinkPreviewMode(A_ROOM_ID) shouldBeEqualTo LinkPreviewMode.ALWAYS
    }

    @Test
    fun `a room inherits the account-wide mode`() {
        defaultPrefs.edit().putString(VectorPreferences.SETTINGS_LINK_PREVIEW_MODE_KEY, LinkPreviewMode.ENCRYPTED_ROOMS.value).commit()

        vectorPreferences.getLinkPreviewMode(A_ROOM_ID) shouldBeEqualTo LinkPreviewMode.ENCRYPTED_ROOMS
        vectorPreferences.getRoomLinkPreviewOverride(A_ROOM_ID).shouldBeNull()
    }

    @Test
    fun `a room override wins over the account-wide mode, and only for that room`() {
        defaultPrefs.edit().putString(VectorPreferences.SETTINGS_LINK_PREVIEW_MODE_KEY, LinkPreviewMode.NEVER.value).commit()

        vectorPreferences.setRoomLinkPreviewOverride(A_ROOM_ID, LinkPreviewMode.ALWAYS)

        vectorPreferences.getLinkPreviewMode(A_ROOM_ID) shouldBeEqualTo LinkPreviewMode.ALWAYS
        vectorPreferences.getLinkPreviewMode(ANOTHER_ROOM_ID) shouldBeEqualTo LinkPreviewMode.NEVER
        vectorPreferences.getLinkPreviewMode() shouldBeEqualTo LinkPreviewMode.NEVER
    }

    @Test
    fun `clearing a room override puts the room back on the account-wide mode`() {
        defaultPrefs.edit().putString(VectorPreferences.SETTINGS_LINK_PREVIEW_MODE_KEY, LinkPreviewMode.DIRECT_MESSAGES.value).commit()
        vectorPreferences.setRoomLinkPreviewOverride(A_ROOM_ID, LinkPreviewMode.NEVER)

        vectorPreferences.setRoomLinkPreviewOverride(A_ROOM_ID, null)

        vectorPreferences.getRoomLinkPreviewOverride(A_ROOM_ID).shouldBeNull()
        vectorPreferences.getLinkPreviewMode(A_ROOM_ID) shouldBeEqualTo LinkPreviewMode.DIRECT_MESSAGES
    }

    @Test
    fun `the room override is stored where the SDK looks for it`() {
        vectorPreferences.setRoomLinkPreviewOverride(A_ROOM_ID, LinkPreviewMode.NEVER)

        // The SDK reads these very keys from the same store; see DefaultLightweightSettingsStorage.
        defaultPrefs.getString("SETTINGS_LINK_PREVIEW_MODE_KEY_$A_ROOM_ID", null) shouldBeEqualTo "never"
    }
}
