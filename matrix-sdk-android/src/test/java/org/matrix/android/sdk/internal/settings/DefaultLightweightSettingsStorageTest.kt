/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.settings

import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.settings.LinkPreviewSource
import org.matrix.android.sdk.internal.platform.SharedPreferencesKeyValueStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private const val A_ROOM_ID = "!room:example.org"
private const val ANOTHER_ROOM_ID = "!other:example.org"
private const val A_USER_ID = "@alice:example.org"
private const val ANOTHER_USER_ID = "@bob:example.org"

// The keys the app writes; see im.vector.app.features.settings.VectorPreferences.
private const val ENCRYPTED_KEY = "SETTINGS_LINK_PREVIEW_ENCRYPTED_KEY"
private const val UNENCRYPTED_KEY = "SETTINGS_LINK_PREVIEW_UNENCRYPTED_KEY"
private const val ROOM_KEY = "SETTINGS_LINK_PREVIEW_SOURCE_KEY"

@RunWith(RobolectricTestRunner::class)
internal class DefaultLightweightSettingsStorageTest {

    private val factory = SharedPreferencesKeyValueStoreFactory(RuntimeEnvironment.getApplication())
    private val appPreferences = factory.defaultStore()

    private val storage = DefaultLightweightSettingsStorage(
            storeFactory = factory,
            matrixConfiguration = mockk(relaxed = true)
    )

    @Test
    fun `reactions are hidden out of the box, and the app's toggle is read`() {
        storage.areReactionsShownInTimeline() shouldBeEqualTo false

        appPreferences.putBoolean("SETTINGS_SHOW_REACTIONS_KEY", true)

        storage.areReactionsShownInTimeline() shouldBeEqualTo true
    }

    @Test
    fun `out of the box, encrypted rooms preview on the device and the others on the homeserver`() {
        storage.getLinkPreviewSource(A_USER_ID, A_ROOM_ID, isEncrypted = true) shouldBeEqualTo LinkPreviewSource.DEVICE
        storage.getLinkPreviewSource(A_USER_ID, A_ROOM_ID, isEncrypted = false) shouldBeEqualTo LinkPreviewSource.SERVER
    }

    @Test
    fun `each kind of room reads its own account-wide setting`() {
        appPreferences.putString("${ENCRYPTED_KEY}_$A_USER_ID", "none")
        appPreferences.putString("${UNENCRYPTED_KEY}_$A_USER_ID", "device")

        storage.getLinkPreviewSource(A_USER_ID, A_ROOM_ID, isEncrypted = true) shouldBeEqualTo LinkPreviewSource.NONE
        storage.getLinkPreviewSource(A_USER_ID, A_ROOM_ID, isEncrypted = false) shouldBeEqualTo LinkPreviewSource.DEVICE
    }

    @Test
    fun `settings are per account`() {
        appPreferences.putString("${ENCRYPTED_KEY}_$A_USER_ID", "none")
        appPreferences.putString("${ROOM_KEY}_${A_USER_ID}_$A_ROOM_ID", "server")

        storage.getLinkPreviewSource(ANOTHER_USER_ID, ANOTHER_ROOM_ID, isEncrypted = true) shouldBeEqualTo LinkPreviewSource.DEVICE
        storage.getLinkPreviewSource(ANOTHER_USER_ID, A_ROOM_ID, isEncrypted = false) shouldBeEqualTo LinkPreviewSource.SERVER
        storage.getLinkPreviewSource(ANOTHER_USER_ID, A_ROOM_ID, isEncrypted = true) shouldBeEqualTo LinkPreviewSource.DEVICE
    }

    @Test
    fun `a room override wins over the account-wide setting, and only for that room`() {
        appPreferences.putString("${ENCRYPTED_KEY}_$A_USER_ID", "device")
        appPreferences.putString("${ROOM_KEY}_${A_USER_ID}_$A_ROOM_ID", "server")

        storage.getLinkPreviewSource(A_USER_ID, A_ROOM_ID, isEncrypted = true) shouldBeEqualTo LinkPreviewSource.SERVER
        storage.getLinkPreviewSource(A_USER_ID, ANOTHER_ROOM_ID, isEncrypted = true) shouldBeEqualTo LinkPreviewSource.DEVICE
    }

    @Test
    fun `a value this version does not know falls back to the default`() {
        appPreferences.putString("${ENCRYPTED_KEY}_$A_USER_ID", "something-from-the-future")
        appPreferences.putString("${ROOM_KEY}_${A_USER_ID}_$A_ROOM_ID", "something-from-the-future")

        storage.getLinkPreviewSource(A_USER_ID, A_ROOM_ID, isEncrypted = true) shouldBeEqualTo LinkPreviewSource.DEVICE
    }
}
