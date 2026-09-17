/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.summary

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DirectRoomsCacheTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var stores: SessionStores
    private val cache = DirectRoomsCache()

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = SessionSqlDatabase.Schema)
        stores = SessionStores(SessionSqlDatabase(driver))
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `no account data means no room is direct`() {
        cache.directUserId(stores, "!a:hs").shouldBeNull()
    }

    @Test
    fun `a room listed under a user is that user's DM`() {
        storeDirect("""{"@bob:hs":["!a:hs"]}""")

        cache.directUserId(stores, "!a:hs") shouldBeEqualTo "@bob:hs"
    }

    @Test
    fun `a room nobody lists is not a DM`() {
        storeDirect("""{"@bob:hs":["!a:hs"]}""")

        cache.directUserId(stores, "!other:hs").shouldBeNull()
    }

    @Test
    fun `every room of a multi-room entry is that user's DM`() {
        storeDirect("""{"@bob:hs":["!a:hs","!b:hs"]}""")

        cache.directUserId(stores, "!a:hs") shouldBeEqualTo "@bob:hs"
        cache.directUserId(stores, "!b:hs") shouldBeEqualTo "@bob:hs"
    }

    /** m.direct can list one room under two users; the answer has to be stable rather than map iteration order. */
    @Test
    fun `a room claimed by two users resolves to the first`() {
        storeDirect("""{"@bob:hs":["!a:hs"],"@carol:hs":["!a:hs"]}""")

        cache.directUserId(stores, "!a:hs") shouldBeEqualTo "@bob:hs"
        cache.directUserId(stores, "!a:hs") shouldBeEqualTo "@bob:hs"
    }

    @Test
    fun `the map is held until it is invalidated`() {
        storeDirect("""{"@bob:hs":["!a:hs"]}""")
        cache.directUserId(stores, "!a:hs") shouldBeEqualTo "@bob:hs"

        storeDirect("""{"@bob:hs":["!a:hs","!b:hs"]}""")

        cache.directUserId(stores, "!b:hs").shouldBeNull()
    }

    @Test
    fun `invalidating picks up the new map`() {
        storeDirect("""{"@bob:hs":["!a:hs"]}""")
        cache.directUserId(stores, "!a:hs") shouldBeEqualTo "@bob:hs"

        storeDirect("""{"@carol:hs":["!a:hs"]}""")
        cache.invalidate()

        cache.directUserId(stores, "!a:hs") shouldBeEqualTo "@carol:hs"
    }

    @Test
    fun `a room dropped from m dot direct stops being a DM after invalidation`() {
        storeDirect("""{"@bob:hs":["!a:hs"]}""")
        cache.directUserId(stores, "!a:hs") shouldBeEqualTo "@bob:hs"

        storeDirect("""{}""")
        cache.invalidate()

        cache.directUserId(stores, "!a:hs").shouldBeNull()
    }

    private fun storeDirect(json: String) {
        stores.accountData.upsertUserAccountData(UserAccountDataTypes.TYPE_DIRECT_MESSAGES, json)
    }
}
