/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.sync.model.RoomSync
import org.matrix.android.sdk.api.session.sync.model.RoomsSyncResponse
import org.matrix.android.sdk.internal.database.model.RoomEntity
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.session.sync.handler.room.SqlRoomSyncHandler

class RoomSyncHistoryScopeTest {

    @Test
    fun `excluded rooms join without scheduling preview or rejoin history requests`() {
        listOf(Membership.NONE, Membership.LEAVE, Membership.BAN).forEach { previousMembership ->
            val aggregator = handleJoin(previousMembership, canSyncTimeline = false)

            assertEquals(emptySet<String>(), aggregator.newlyJoinedRooms)
            assertEquals(emptySet<String>(), aggregator.rejoinedRoomsToReanchor)
        }
    }

    @Test
    fun `synced joins still request preview history and reanchor retained history`() {
        val joined = handleJoin(Membership.NONE, canSyncTimeline = true)
        assertEquals(setOf("!room:example.org"), joined.newlyJoinedRooms)
        assertEquals(emptySet<String>(), joined.rejoinedRoomsToReanchor)

        val rejoined = handleJoin(Membership.LEAVE, canSyncTimeline = true)
        assertEquals(setOf("!room:example.org"), rejoined.newlyJoinedRooms)
        assertEquals(setOf("!room:example.org"), rejoined.rejoinedRoomsToReanchor)
    }

    private fun handleJoin(previousMembership: Membership, canSyncTimeline: Boolean): SyncResponsePostTreatmentAggregator {
        val roomId = "!room:example.org"
        val room = RoomEntity(roomId = roomId).apply { membership = previousMembership }
        val stores = mockk<SessionStores>(relaxed = true)
        every { stores.room.get(roomId) } returns room
        val handler = SqlRoomSyncHandler(
                readReceiptHandler = mockk(relaxed = true),
                roomSummaryUpdater = mockk(relaxed = true),
                roomMemberEventHandler = mockk(relaxed = true),
                roomChangeMembershipStateDataSource = mockk(relaxed = true),
                roomTagHandler = mockk(relaxed = true),
                roomFullyReadHandler = mockk(relaxed = true),
                roomMarkedUnreadHandler = mockk(relaxed = true),
                typingUsersTracker = mockk(relaxed = true),
                typingUserProfileResolver = mockk(relaxed = true),
                userId = "@me:example.org",
                homeServerCapabilitiesService = mockk(relaxed = true),
                threadSummaryHelper = mockk(relaxed = true),
                lightweightSettingsStorage = mockk(relaxed = true),
                timelineInput = mockk(relaxed = true),
                liveEventService = mockk(relaxed = true),
                clock = mockk(relaxed = true),
                unRequestedForwardManager = mockk(relaxed = true),
                localEchoRepository = mockk(relaxed = true),
        )
        val aggregator = SyncResponsePostTreatmentAggregator()
        handler.handle(
                stores,
                RoomsSyncResponse(join = mapOf(roomId to RoomSync(canSyncTimeline = canSyncTimeline))),
                isInitialSync = false,
                aggregator = aggregator,
        )
        assertEquals(Membership.JOIN, room.membership)
        return aggregator
    }
}
