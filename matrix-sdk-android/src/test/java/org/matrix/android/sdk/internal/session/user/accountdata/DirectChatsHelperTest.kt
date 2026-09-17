/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.user.accountdata

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DirectChatsHelperTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var stores: SessionStores
    private lateinit var helper: DirectChatsHelper

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = SessionSqlDatabase.Schema)
        stores = SessionStores(SessionSqlDatabase(driver))
        helper = DirectChatsHelper(stores, org.matrix.android.sdk.internal.session.room.summary.DirectRoomsCache())
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a room the client has not synced yet is kept`() {
        givenStoredDirects("""{"@alice:hs":["!synced:hs"],"@bob:hs":["!notsyncedyet:hs"]}""")
        givenLocalDirectRoom("!synced:hs", "@alice:hs")

        val result = helper.getDirectMessagesToPut()

        result["@bob:hs"] shouldBeEqualTo listOf("!notsyncedyet:hs")
        result["@alice:hs"] shouldBeEqualTo listOf("!synced:hs")
    }

    @Test
    fun `a locally known direct room missing from the stored map is added`() {
        givenStoredDirects("""{"@alice:hs":["!old:hs"]}""")
        givenLocalDirectRoom("!fresh:hs", "@carol:hs")

        val result = helper.getDirectMessagesToPut()

        result["@carol:hs"] shouldBeEqualTo listOf("!fresh:hs")
        result["@alice:hs"].orEmpty() shouldContain "!old:hs"
    }

    @Test
    fun `nothing stored yet falls back to what is known locally`() {
        givenLocalDirectRoom("!only:hs", "@dave:hs")

        helper.getDirectMessagesToPut()["@dave:hs"] shouldBeEqualTo listOf("!only:hs")
    }

    @Test
    fun `a filtered room is removed from every entry`() {
        givenStoredDirects("""{"@alice:hs":["!gone:hs","!kept:hs"],"@bob:hs":["!gone:hs"]}""")

        val result = helper.getDirectMessagesToPut(filterRoomId = "!gone:hs")

        result["@alice:hs"] shouldBeEqualTo listOf("!kept:hs")
        // The entry emptied by the removal goes away rather than being written back as an empty list.
        (result["@bob:hs"] == null) shouldBeEqualTo true
    }

    private fun givenStoredDirects(json: String) {
        stores.accountData.upsertUserAccountData(UserAccountDataTypes.TYPE_DIRECT_MESSAGES, json)
    }

    private fun givenLocalDirectRoom(roomId: String, directUserId: String) {
        stores.roomSummary.upsert(
                RoomSummaryEntity(roomId).apply {
                    membership = Membership.JOIN
                    isDirect = true
                    this.directUserId = directUserId
                }
        )
    }
}
