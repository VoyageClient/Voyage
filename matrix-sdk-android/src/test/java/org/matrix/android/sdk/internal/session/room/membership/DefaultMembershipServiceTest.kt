/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.membership

import io.mockk.mockk
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.room.members.roomMemberQueryParams
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.internal.database.model.RoomMemberSummaryEntity
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DefaultMembershipServiceTest {
    private val db = FakeSessionDatabase()
    private val roomId = "!room:example.org"
    private val service = DefaultMembershipService(
            roomId = roomId,
            database = db.database,
            dispatcher = db.dispatcher,
            stores = db.stores,
            loadRoomMembersTask = mockk(),
            inviteTask = mockk(),
            inviteThreePidTask = mockk(),
            membershipAdminTask = mockk(),
            roomDataSource = mockk(),
            cryptoService = mockk(),
            roomMemberColorCache = mockk(),
            ambiguousDisplayNameCache = mockk(),
            homeServerCapabilitiesDataSource = mockk(),
            delayedEventService = mockk(),
            userId = "@alice:example.org",
    )

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `continuous member imports emit progress before the final write`() = runBlocking<Unit> {
        val updates = Channel<List<RoomMemberSummary>>(Channel.UNLIMITED)
        val collector = launch { service.getRoomMembersFlow(roomMemberQueryParams {}).collect { updates.send(it) } }
        var writes = 0
        try {
            withTimeout(5000) { updates.receive() } shouldBeEqualTo emptyList()
            val writer = launch {
                repeat(50) { index ->
                    withContext(db.dispatcher) {
                        db.stores.roomMember.upsert(
                                RoomMemberSummaryEntity(
                                        primaryKey = "member-$index", userId = "@member-$index:example.org",
                                        roomId = roomId, displayName = "Member $index",
                                ).apply { membership = Membership.JOIN }
                        )
                    }
                    writes++
                    delay(20)
                }
            }
            try {
                val progress = withTimeout(750) { updates.receive() }
                progress.isNotEmpty() shouldBeEqualTo true
                (writes < 50) shouldBeEqualTo true
            } finally {
                writer.cancelAndJoin()
            }
        } finally {
            collector.cancelAndJoin()
            updates.close()
        }
    }
}
