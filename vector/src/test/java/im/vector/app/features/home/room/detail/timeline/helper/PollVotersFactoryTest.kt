/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import im.vector.app.features.settings.VectorPreferences
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.model.EventAnnotationsSummary
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.PollResponseAggregatedSummary
import org.matrix.android.sdk.api.session.room.model.PollSummaryContent
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.session.room.model.VoteInfo

private const val A_ROOM_ID = "!room:server"
private const val A_POLL_EVENT_ID = "\$poll"

class PollVotersFactoryTest {

    private val room = mockk<Room>(relaxed = true)
    private val session = mockk<Session>(relaxed = true).also {
        every { it.roomService().getRoom(A_ROOM_ID) } returns room
    }
    private val vectorPreferences = mockk<VectorPreferences>(relaxed = true).also {
        every { it.hideAvatarsInHiddenMediaRooms() } returns false
    }
    private val factory = PollVotersFactory(session, vectorPreferences)

    @Test
    fun `returns only voters of the option, newest first, with member profiles`() {
        givenVotes(
                VoteInfo("@alice:server", "a", 10),
                VoteInfo("@bob:server", "b", 20),
                VoteInfo("@carol:server", "a", 30),
        )
        every { room.membershipService().getRoomMember("@alice:server") } returns
                RoomMemberSummary(Membership.JOIN, "@alice:server", displayName = "Alice", avatarUrl = "mxc://a")
        every { room.membershipService().getRoomMember("@carol:server") } returns null

        val voters = factory.create(A_ROOM_ID, A_POLL_EVENT_ID, "a")

        voters.map { it.userId } shouldBeEqualTo listOf("@carol:server", "@alice:server")
        voters[0].displayName shouldBeEqualTo null
        voters[1].displayName shouldBeEqualTo "Alice"
        voters[1].avatarUrl shouldBeEqualTo "mxc://a"
        voters[1].timestamp shouldBeEqualTo 10L
    }

    @Test
    fun `returns nothing when the poll has no summary`() {
        every { room.relationService().getEventAnnotationsSummary(A_POLL_EVENT_ID) } returns null

        factory.create(A_ROOM_ID, A_POLL_EVENT_ID, "a").shouldBeEmpty()
    }

    private fun givenVotes(vararg votes: VoteInfo) {
        every { room.relationService().getEventAnnotationsSummary(A_POLL_EVENT_ID) } returns EventAnnotationsSummary(
                pollResponseSummary = PollResponseAggregatedSummary(
                        aggregatedContent = PollSummaryContent(votes = votes.toList()),
                        sourceEvents = emptyList(),
                        localEchos = emptyList(),
                        encryptedRelatedEventIds = emptyList(),
                )
        )
    }
}
