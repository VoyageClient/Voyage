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
import org.matrix.android.sdk.api.settings.LinkPreviewMode
import org.matrix.android.sdk.internal.platform.SharedPreferencesKeyValueStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private const val A_ROOM_ID = "!room:example.org"
private const val ANOTHER_ROOM_ID = "!other:example.org"

// The key the app writes; see im.vector.app.features.settings.VectorPreferences.
private const val MODE_KEY = "SETTINGS_LINK_PREVIEW_MODE_KEY"

@RunWith(RobolectricTestRunner::class)
internal class DefaultLightweightSettingsStorageTest {

    private val factory = SharedPreferencesKeyValueStoreFactory(RuntimeEnvironment.getApplication())
    private val appPreferences = factory.defaultStore()

    private val storage = DefaultLightweightSettingsStorage(
            storeFactory = factory,
            matrixConfiguration = mockk(relaxed = true)
    )

    @Test
    fun `previews are fetched by this device out of the box`() {
        storage.getLinkPreviewMode(A_ROOM_ID) shouldBeEqualTo LinkPreviewMode.ALWAYS
    }

    @Test
    fun `the account-wide mode the app wrote is read`() {
        appPreferences.putString(MODE_KEY, "encrypted")

        storage.getLinkPreviewMode(A_ROOM_ID) shouldBeEqualTo LinkPreviewMode.ENCRYPTED_ROOMS
    }

    @Test
    fun `a room override wins over the account-wide mode, and only for that room`() {
        appPreferences.putString(MODE_KEY, "never")
        appPreferences.putString("${MODE_KEY}_$A_ROOM_ID", "always")

        storage.getLinkPreviewMode(A_ROOM_ID) shouldBeEqualTo LinkPreviewMode.ALWAYS
        storage.getLinkPreviewMode(ANOTHER_ROOM_ID) shouldBeEqualTo LinkPreviewMode.NEVER
    }

    @Test
    fun `a room override of direct messages is understood too`() {
        appPreferences.putString("${MODE_KEY}_$A_ROOM_ID", "direct")

        storage.getLinkPreviewMode(A_ROOM_ID) shouldBeEqualTo LinkPreviewMode.DIRECT_MESSAGES
    }

    @Test
    fun `a mode this version does not know falls back to fetching on the device`() {
        appPreferences.putString(MODE_KEY, "something-from-the-future")

        storage.getLinkPreviewMode(A_ROOM_ID) shouldBeEqualTo LinkPreviewMode.ALWAYS
    }
}
