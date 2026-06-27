/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.store.db.sql

import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OlmSessionSqlStoreTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var store: OlmSessionSqlStore

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = CryptoSqlDatabase.Schema)
        store = OlmSessionSqlStore(CryptoSqlDatabase(driver))
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `session ids are ordered by last received timestamp descending`() {
        store.upsert("s1|dev", "s1", "dev", "data1", 100)
        store.upsert("s2|dev", "s2", "dev", "data2", 300)
        store.upsert("s3|dev", "s3", "dev", "data3", 200)

        store.getDeviceSessionIds("dev") shouldBeEqualTo listOf("s2", "s3", "s1")
        store.getLastUsedSessionId("dev") shouldBeEqualTo "s2"
        store.getDeviceSessionIds("other") shouldBeEqualTo emptyList()
    }

    @Test
    fun `get returns the stored blob and upsert replaces`() {
        store.upsert("s1|dev", "s1", "dev", "data1", 100)
        store.get("s1|dev")!!.olm_session_data shouldBeEqualTo "data1"

        store.upsert("s1|dev", "s1", "dev", "data1-updated", 500)
        store.get("s1|dev")!!.olm_session_data shouldBeEqualTo "data1-updated"
        store.getLastUsedSessionId("dev") shouldBeEqualTo "s1"
    }
}
