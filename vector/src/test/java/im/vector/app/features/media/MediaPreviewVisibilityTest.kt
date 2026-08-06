/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import im.vector.app.features.settings.MediaPreviewMode
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.test.fakes.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBe
import org.junit.Test
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.model.RoomSummary

private const val A_ROOM_ID = "!room:example.org"
private const val ANOTHER_ROOM_ID = "!other:example.org"

class MediaPreviewVisibilityTest {

    private val preferences = InMemorySharedPreferences()
    private val vectorPreferences = mockk<VectorPreferences>()

    init {
        // Delegate the resolution under test to the real implementation, backed by real storage.
        every { vectorPreferences.getMediaPreviewMode(any()) } answers {
            val roomId = firstArg<String?>()
            roomId?.let { id ->
                preferences.getString("SETTINGS_MEDIA_PREVIEW_KEY_$id", null)?.let { MediaPreviewMode.fromValue(it) }
            }
                    ?: MediaPreviewMode.fromValue(preferences.getString("SETTINGS_MEDIA_PREVIEW_KEY", null))
        }
    }

    private fun summary(
            roomId: String = A_ROOM_ID,
            isDirect: Boolean = false,
            joinRules: RoomJoinRules? = RoomJoinRules.PUBLIC,
    ) = mockk<RoomSummary> {
        every { this@mockk.roomId } returns roomId
        every { this@mockk.isDirect } returns isDirect
        every { this@mockk.joinRules } returns joinRules
    }

    private fun setGlobal(mode: MediaPreviewMode) {
        preferences.edit().putString("SETTINGS_MEDIA_PREVIEW_KEY", mode.value).apply()
    }

    private fun setRoom(roomId: String, mode: MediaPreviewMode?) {
        preferences.edit().apply {
            if (mode == null) remove("SETTINGS_MEDIA_PREVIEW_KEY_$roomId")
            else putString("SETTINGS_MEDIA_PREVIEW_KEY_$roomId", mode.value)
        }.apply()
    }

    @Test
    fun `given room set to show, then media is not hidden`() {
        setGlobal(MediaPreviewMode.ALWAYS_HIDE)
        setRoom(A_ROOM_ID, MediaPreviewMode.ALWAYS_SHOW)

        isMediaHiddenInRoom(summary(), vectorPreferences) shouldBe false
    }

    @Test
    fun `given room set to hide, then media is hidden`() {
        setGlobal(MediaPreviewMode.ALWAYS_SHOW)
        setRoom(A_ROOM_ID, MediaPreviewMode.ALWAYS_HIDE)

        isMediaHiddenInRoom(summary(), vectorPreferences) shouldBe true
    }

    @Test
    fun `given no room override, then the account-wide mode applies`() {
        setGlobal(MediaPreviewMode.ALWAYS_HIDE)
        setRoom(A_ROOM_ID, null)

        isMediaHiddenInRoom(summary(), vectorPreferences) shouldBe true
    }

    @Test
    fun `given an override is cleared, then the account-wide mode applies again`() {
        setGlobal(MediaPreviewMode.ALWAYS_HIDE)
        setRoom(A_ROOM_ID, MediaPreviewMode.ALWAYS_SHOW)
        isMediaHiddenInRoom(summary(), vectorPreferences) shouldBe false

        setRoom(A_ROOM_ID, null)

        isMediaHiddenInRoom(summary(), vectorPreferences) shouldBe true
    }

    @Test
    fun `given an override, then it does not affect other rooms`() {
        setGlobal(MediaPreviewMode.ALWAYS_HIDE)
        setRoom(A_ROOM_ID, MediaPreviewMode.ALWAYS_SHOW)

        isMediaHiddenInRoom(summary(roomId = ANOTHER_ROOM_ID), vectorPreferences) shouldBe true
    }

    @Test
    fun `given private mode, then hiding follows the room join rules`() {
        setGlobal(MediaPreviewMode.PRIVATE)

        isMediaHiddenInRoom(summary(joinRules = RoomJoinRules.INVITE), vectorPreferences) shouldBe false
        isMediaHiddenInRoom(summary(joinRules = RoomJoinRules.PUBLIC), vectorPreferences) shouldBe true
    }

    @Test
    fun `given direct mode, then hiding follows whether the room is a DM`() {
        setGlobal(MediaPreviewMode.DIRECT)

        isMediaHiddenInRoom(summary(isDirect = true), vectorPreferences) shouldBe false
        isMediaHiddenInRoom(summary(isDirect = false), vectorPreferences) shouldBe true
    }

    @Test
    fun `given a null summary, then media is not hidden`() {
        setGlobal(MediaPreviewMode.ALWAYS_SHOW)

        isMediaHiddenInRoom(null, vectorPreferences) shouldBe false
    }
}
