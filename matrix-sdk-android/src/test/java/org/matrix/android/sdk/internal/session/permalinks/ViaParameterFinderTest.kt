/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.permalinks

import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.internal.database.model.RoomMemberSummaryEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

private const val ROOM_ID = "!room:hs"
private const val ME = "@me:home.org"

@RunWith(RobolectricTestRunner::class)
class ViaParameterFinderTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var stores: SessionStores
    private lateinit var finder: ViaParameterFinder

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = SessionSqlDatabase.Schema)
        stores = SessionStores(SessionSqlDatabase(driver))
        finder = ViaParameterFinder(ME, { mockk() }, mockk(relaxed = true), stores)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `our own server leads, then the servers with the most joined members`() {
        member("@a:big.org")
        member("@b:big.org")
        member("@c:big.org")
        member("@d:mid.org")
        member("@e:mid.org")
        member("@f:small.org")

        finder.computeViaParams(ME, ROOM_ID, 3) shouldBeEqualTo listOf("home.org", "big.org", "mid.org")
    }

    @Test
    fun `members who left do not count`() {
        member("@a:gone.org", Membership.LEAVE)
        member("@b:gone.org", Membership.LEAVE)
        member("@c:here.org")

        finder.computeViaParams(ME, ROOM_ID, 3) shouldBeEqualTo listOf("home.org", "here.org")
    }

    @Test
    fun `a server name with a port keeps it`() {
        member("@a:hs.org:8448")

        finder.computeViaParams(ME, ROOM_ID, 3) shouldBeEqualTo listOf("home.org", "hs.org:8448")
    }

    @Test
    fun `repeated asks for one room reuse the counts for a while`() {
        member("@a:first.org")
        finder.computeViaParams(ME, ROOM_ID, 3)
        member("@b:second.org")

        finder.computeViaParams(ME, ROOM_ID, 3) shouldBeEqualTo listOf("home.org", "first.org")

        ShadowSystemClock.advanceBy(Duration.ofMinutes(6))

        finder.computeViaParams(ME, ROOM_ID, 3) shouldBeEqualTo listOf("home.org", "first.org", "second.org")
    }

    private fun member(userId: String, membership: Membership = Membership.JOIN) {
        stores.roomMember.upsert(
                RoomMemberSummaryEntity(primaryKey = "${ROOM_ID}_$userId", userId = userId, roomId = ROOM_ID)
                        .also { it.membership = membership }
        )
    }
}
