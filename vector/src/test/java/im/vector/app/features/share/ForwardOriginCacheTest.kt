/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.share

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.UUID

private const val ROOM_ID = "!busy:hs"
private const val SENDER = "@sender:hs"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ForwardOriginCacheTest {

    // The cache outlives a test, so each one gets a session of its own.
    private val session = mockk<Session>(relaxed = true).also {
        every { it.sessionId } returns UUID.randomUUID().toString()
    }

    private fun summary(name: String) = mockk<RoomSummary>().also {
        every { it.displayName } returns name
        every { it.membership } returns Membership.JOIN
    }

    @Test
    fun `forwards from one room look its summary up once`() {
        every { session.roomService().getRoomSummary(ROOM_ID) } returns summary("Busy room")

        repeat(20) { session.forwardOriginRoom(ROOM_ID).displayName shouldBeEqualTo "Busy room" }

        verify(exactly = 1) { session.roomService().getRoomSummary(ROOM_ID) }
    }

    @Test
    fun `a renamed room shows its new name once the entry expires`() {
        every { session.roomService().getRoomSummary(ROOM_ID) } returns summary("Old name")
        session.forwardOriginRoom(ROOM_ID)
        every { session.roomService().getRoomSummary(ROOM_ID) } returns summary("New name")

        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))

        session.forwardOriginRoom(ROOM_ID).displayName shouldBeEqualTo "New name"
    }

    @Test
    fun `a room we know nothing about is remembered as unknown`() {
        every { session.roomService().getRoomSummary(ROOM_ID) } returns null

        session.forwardOriginRoom(ROOM_ID).known shouldBeEqualTo false
        session.forwardOriginRoom(ROOM_ID).known shouldBeEqualTo false

        verify(exactly = 1) { session.roomService().getRoomSummary(ROOM_ID) }
    }

    /** The profile fetch it starts fills the name in; remembering the miss would hide it for a minute. */
    @Test
    fun `a sender with no name yet is asked again`() {
        every { session.roomService().getRoom(any()) } returns null
        every { session.userService().getUser(SENDER) } returns null
        every { session.profileService().getCachedProfile(SENDER) } returns null

        session.forwardedSenderDisplayName(SENDER, ROOM_ID) shouldBeEqualTo null
        every { session.profileService().getCachedProfile(SENDER) } returns mapOf("displayname" to "Sender")

        session.forwardedSenderDisplayName(SENDER, ROOM_ID) shouldBeEqualTo "Sender"
    }

    @Test
    fun `accounts do not share entries`() {
        val other = mockk<Session>(relaxed = true).also { every { it.sessionId } returns UUID.randomUUID().toString() }
        every { session.roomService().getRoomSummary(ROOM_ID) } returns summary("Mine")
        every { other.roomService().getRoomSummary(ROOM_ID) } returns summary("Theirs")

        session.forwardOriginRoom(ROOM_ID).displayName shouldBeEqualTo "Mine"
        other.forwardOriginRoom(ROOM_ID).displayName shouldBeEqualTo "Theirs"
    }
}
