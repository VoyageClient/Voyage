/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.raw

import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.internal.database.global.GlobalSqlDatabase
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.matrix.android.sdk.internal.database.sqldelight.newDatabaseDispatcher
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RawCacheStoreTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var store: RawCacheStore

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = GlobalSqlDatabase.Schema)
        store = RawCacheStore(GlobalSqlDatabase(driver), newDatabaseDispatcher("test-raw"))
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `get returns null when absent`() {
        runBlocking {
            store.get("https://missing") shouldBe null
        }
    }

    @Test
    fun `put then get round-trips data and timestamp`() {
        runBlocking {
            store.put("https://a", "payload", 123L)

            val entry = store.get("https://a")
            entry shouldNotBe null
            entry!!.data shouldBeEqualTo "payload"
            entry.lastUpdatedTimestamp shouldBeEqualTo 123L
        }
    }

    @Test
    fun `put replaces the entry for an existing url`() {
        runBlocking {
            store.put("https://a", "v1", 1L)
            store.put("https://a", "v2", 2L)

            val entry = store.get("https://a")
            entry!!.data shouldBeEqualTo "v2"
            entry.lastUpdatedTimestamp shouldBeEqualTo 2L
        }
    }

    @Test
    fun `clear removes all entries`() {
        runBlocking {
            store.put("https://a", "v", 1L)
            store.put("https://b", "v", 1L)

            store.clear()

            store.get("https://a") shouldBe null
            store.get("https://b") shouldBe null
        }
    }
}
