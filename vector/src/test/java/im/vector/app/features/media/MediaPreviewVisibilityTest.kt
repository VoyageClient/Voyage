/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import im.vector.app.features.settings.MediaPreviewMode
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.test.fakes.FakeSession
import im.vector.app.test.fakes.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBe
import org.junit.Test
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.util.MatrixItem

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
            membership: Membership = Membership.JOIN,
    ) = mockk<RoomSummary> {
        every { this@mockk.roomId } returns roomId
        every { this@mockk.isDirect } returns isDirect
        every { this@mockk.joinRules } returns joinRules
        every { this@mockk.membership } returns membership
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

    @Test
    fun `given avatar hiding is on and media hidden, then room, space and other users lose their avatar`() {
        givenAvatarHiding(true)
        setGlobal(MediaPreviewMode.PRIVATE)
        val session = sessionWith(summary(joinRules = RoomJoinRules.PUBLIC))

        MatrixItem.RoomItem(A_ROOM_ID, "Room", AN_AVATAR).withAvatarHiddenIn(null, session, vectorPreferences).avatarUrl shouldBe null
        MatrixItem.SpaceItem(A_ROOM_ID, "Space", AN_AVATAR).withAvatarHiddenIn(null, session, vectorPreferences).avatarUrl shouldBe null
        MatrixItem.UserItem(BOB, "Bob", AN_AVATAR).withAvatarHiddenIn(A_ROOM_ID, session, vectorPreferences).avatarUrl shouldBe null
    }

    @Test
    fun `given avatar hiding is on, then our own avatar and a user outside any room are kept`() {
        givenAvatarHiding(true)
        setGlobal(MediaPreviewMode.ALWAYS_HIDE)
        val session = sessionWith(summary())

        MatrixItem.UserItem(ME, "Me", AN_AVATAR).withAvatarHiddenIn(A_ROOM_ID, session, vectorPreferences).avatarUrl shouldBe AN_AVATAR
        MatrixItem.UserItem(BOB, "Bob", AN_AVATAR).withAvatarHiddenIn(null, session, vectorPreferences).avatarUrl shouldBe AN_AVATAR
    }

    @Test
    fun `given an invite, then avatars are kept even when media is always hidden`() {
        givenAvatarHiding(true)
        setGlobal(MediaPreviewMode.ALWAYS_HIDE)
        val session = sessionWith(summary(membership = Membership.INVITE))

        MatrixItem.RoomItem(A_ROOM_ID, "Room", AN_AVATAR).withAvatarHiddenIn(null, session, vectorPreferences).avatarUrl shouldBe AN_AVATAR
        MatrixItem.UserItem(BOB, "Bob", AN_AVATAR).withAvatarHiddenIn(A_ROOM_ID, session, vectorPreferences).avatarUrl shouldBe AN_AVATAR
    }

    @Test
    fun `given media is shown or avatar hiding is off, then avatars are kept`() {
        val session = sessionWith(summary())
        val room = MatrixItem.RoomItem(A_ROOM_ID, "Room", AN_AVATAR)

        givenAvatarHiding(true)
        setGlobal(MediaPreviewMode.ALWAYS_SHOW)
        room.withAvatarHiddenIn(null, session, vectorPreferences).avatarUrl shouldBe AN_AVATAR

        givenAvatarHiding(false)
        setGlobal(MediaPreviewMode.ALWAYS_HIDE)
        room.withAvatarHiddenIn(null, session, vectorPreferences).avatarUrl shouldBe AN_AVATAR
    }

    private fun givenAvatarHiding(enabled: Boolean) {
        every { vectorPreferences.hideAvatarsInHiddenMediaRooms() } returns enabled
    }

    private fun sessionWith(roomSummary: RoomSummary) = FakeSession.withRoomSummary(roomSummary).apply { fakeUserId = ME }
}

private const val ME = "@me:example.org"
private const val BOB = "@bob:example.org"
private const val AN_AVATAR = "mxc://example.org/avatar"
