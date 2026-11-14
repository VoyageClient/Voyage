/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.membership

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.internal.session.room.RoomAPI
import java.io.IOException

class OwnMembershipProbeTest {

    private val roomAPI = mockk<RoomAPI>()
    private val probe = OwnMembershipProbe(roomAPI, ME)

    private fun memberEvent(membership: String, sender: String) = Event(
            type = EventType.STATE_ROOM_MEMBER,
            eventId = "\$member-event",
            stateKey = ME,
            senderId = sender,
            content = mapOf("membership" to membership),
    )

    private fun error(httpCode: Int, code: String) = Failure.ServerError(MatrixError(code, "refused"), httpCode)

    private fun givenMemberEvent(event: Event) = coEvery { roomAPI.getRoomMemberEvent(ROOM, ME, any()) } returns event

    private fun givenMemberEventFails(failure: Throwable) = coEvery { roomAPI.getRoomMemberEvent(ROOM, ME, any()) } throws failure

    private fun probe() = runBlocking { probe.probe(ROOM) }

    @Test
    fun `a joined member is left alone`() {
        givenMemberEvent(memberEvent("join", ME))
        probe() shouldBe OwnMembership.Member
    }

    @Test
    fun `a readable kick keeps its event so the sender decides`() {
        val kick = memberEvent("leave", KICKER)
        givenMemberEvent(kick)
        probe() shouldBeEqualTo OwnMembership.Departed(kick, Membership.LEAVE)
    }

    @Test
    fun `a 403 is a departure of unknown cause unless the summary confirms a ban`() {
        givenMemberEventFails(error(403, MatrixError.M_FORBIDDEN))
        coEvery { roomAPI.getRoomSummary(ROOM) } returns RoomSummaryMembership("leave")
        probe() shouldBeEqualTo OwnMembership.Departed(null, null)
    }

    @Test
    fun `a 403 with a summary saying ban is a ban`() {
        givenMemberEventFails(error(403, MatrixError.M_FORBIDDEN))
        coEvery { roomAPI.getRoomSummary(ROOM) } returns RoomSummaryMembership("ban")
        probe() shouldBeEqualTo OwnMembership.Departed(null, Membership.BAN)
    }

    @Test
    fun `an inaccessible summary leaves the cause unknown`() {
        givenMemberEventFails(error(403, MatrixError.M_FORBIDDEN))
        coEvery { roomAPI.getRoomSummary(ROOM) } throws error(404, MatrixError.M_NOT_FOUND)
        probe() shouldBeEqualTo OwnMembership.Departed(null, null)
        coVerify(exactly = 0) { roomAPI.getRoomSummaryUnstable(any()) }
    }

    @Test
    fun `the unstable summary is only tried when the stable one is unrecognized`() {
        givenMemberEventFails(error(403, MatrixError.M_FORBIDDEN))
        coEvery { roomAPI.getRoomSummary(ROOM) } throws error(404, MatrixError.M_UNRECOGNIZED)
        coEvery { roomAPI.getRoomSummaryUnstable(ROOM) } returns RoomSummaryMembership("ban")
        probe() shouldBeEqualTo OwnMembership.Departed(null, Membership.BAN)
    }

    @Test
    fun `other failures prove nothing`() {
        givenMemberEventFails(error(404, MatrixError.M_NOT_FOUND))
        probe() shouldBe OwnMembership.Unknown
        givenMemberEventFails(error(403, MatrixError.M_CONSENT_NOT_GIVEN))
        probe() shouldBe OwnMembership.Unknown
        givenMemberEventFails(IOException("offline"))
        probe() shouldBe OwnMembership.Unknown
    }

    @Test
    fun `bare content from a server ignoring format falls back to the content endpoint`() {
        givenMemberEvent(Event(content = mapOf("membership" to "ban")))
        coEvery { roomAPI.getRoomMemberContent(ROOM, ME) } returns RoomMemberContent(membership = Membership.BAN)
        probe() shouldBeEqualTo OwnMembership.Departed(null, Membership.BAN)
    }

    private companion object {
        const val ME = "@alice:example.org"
        const val KICKER = "@mod:example.org"
        const val ROOM = "!room:example.org"
    }
}
