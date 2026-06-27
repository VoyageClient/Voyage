/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.store.db.sql

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MegolmInboundSqlStoreTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var store: MegolmInboundSqlStore

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = CryptoSqlDatabase.Schema)
        store = MegolmInboundSqlStore(CryptoSqlDatabase(driver))
        store.upsert("sess1|sender1", "sess1", "sender1", "room1", "json1", "pickle1", sharedHistory = false, backedUp = false)
        store.upsert("sess2|sender2", "sess2", "sender2", "room1", "json2", "pickle2", sharedHistory = true, backedUp = false)
        store.upsert("sess3|sender3", "sess3", "sender3", "room2", "json3", "pickle3", sharedHistory = false, backedUp = true)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `get and shared-history filtering`() {
        store.get("sess1|sender1")!!.serialized_olm_inbound_group_session shouldBeEqualTo "pickle1"
        store.getWithSharedHistory("sess2|sender2", true)!!.session_id shouldBeEqualTo "sess2"
        store.getWithSharedHistory("sess1|sender1", true) shouldBe null
    }

    @Test
    fun `query by room and counts`() {
        store.getByRoomId("room1").size shouldBeEqualTo 2
        store.getAll().size shouldBeEqualTo 3
        store.count(onlyBackedUp = false) shouldBeEqualTo 3
        store.count(onlyBackedUp = true) shouldBeEqualTo 1
    }

    @Test
    fun `backup markers`() {
        store.getNotBackedUp(10).size shouldBeEqualTo 2

        store.markBackedUp("sess1|sender1")
        store.count(onlyBackedUp = true) shouldBeEqualTo 2

        store.markAllNotBackedUp()
        store.count(onlyBackedUp = true) shouldBeEqualTo 0
    }

    @Test
    fun `delete removes a session`() {
        store.delete("sess1|sender1")
        store.get("sess1|sender1") shouldBe null
        store.getAll().size shouldBeEqualTo 2
    }
}
