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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val ROOM_ID = "!room:example.org"
private const val OTHER_ROOM_ID = "!other:example.org"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomActivityPreferencesTest {

    private val context = RuntimeEnvironment.getApplication()
    private val storage = PreferenceManager.getDefaultSharedPreferences(context)
    private val preferences = VectorPreferences(
            context = context,
            buildMeta = mockk(relaxed = true),
            vectorFeatures = mockk(relaxed = true),
            defaultPrefs = storage,
    )

    @Test
    fun `read receipt override wins over the account setting in its room only`() {
        storage.edit().putBoolean("SETTINGS_SEND_READ_RECEIPT_KEY", false).apply()
        preferences.getRoomSendReadReceiptsOverride(ROOM_ID).shouldBeNull()
        preferences.sendReadReceipts(ROOM_ID) shouldBeEqualTo false

        preferences.setRoomSendReadReceiptsOverride(ROOM_ID, true)
        preferences.sendReadReceipts(ROOM_ID) shouldBeEqualTo true
        preferences.sendReadReceipts(OTHER_ROOM_ID) shouldBeEqualTo false

        storage.edit().putBoolean("SETTINGS_SEND_READ_RECEIPT_KEY", true).apply()
        preferences.setRoomSendReadReceiptsOverride(ROOM_ID, false)
        preferences.sendReadReceipts(ROOM_ID) shouldBeEqualTo false
        preferences.sendReadReceipts(OTHER_ROOM_ID) shouldBeEqualTo true

        preferences.setRoomSendReadReceiptsOverride(ROOM_ID, null)
        preferences.getRoomSendReadReceiptsOverride(ROOM_ID).shouldBeNull()
        preferences.sendReadReceipts(ROOM_ID) shouldBeEqualTo true
    }

    @Test
    fun `typing override wins over the account setting in its room only`() {
        storage.edit().putBoolean("SETTINGS_SEND_TYPING_NOTIF_KEY", true).apply()
        preferences.getRoomSendTypingNotifsOverride(ROOM_ID).shouldBeNull()
        preferences.sendTypingNotifs(ROOM_ID) shouldBeEqualTo true

        preferences.setRoomSendTypingNotifsOverride(ROOM_ID, false)
        preferences.sendTypingNotifs(ROOM_ID) shouldBeEqualTo false
        preferences.sendTypingNotifs(OTHER_ROOM_ID) shouldBeEqualTo true

        preferences.setRoomSendTypingNotifsOverride(ROOM_ID, null)
        preferences.sendTypingNotifs(ROOM_ID) shouldBeEqualTo true
    }

    @Test
    fun `read receipt and typing overrides are independent`() {
        preferences.setRoomSendReadReceiptsOverride(ROOM_ID, true)
        preferences.getRoomSendTypingNotifsOverride(ROOM_ID).shouldBeNull()
        preferences.setRoomSendTypingNotifsOverride(ROOM_ID, false)
        preferences.getRoomSendReadReceiptsOverride(ROOM_ID) shouldBeEqualTo true
    }
}
