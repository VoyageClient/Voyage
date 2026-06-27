/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sqldelight

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.QueryResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.internal.database.global.GlobalSqlDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FrameworkSqliteDriverTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var database: GlobalSqlDatabase

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = GlobalSqlDatabase.Schema)
        database = GlobalSqlDatabase(driver)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `generated queries round-trip via the custom driver`() {
        val queries = database.rawCacheQueries

        queries.upsert("https://a", "first", 1L)
        queries.selectByUrl("https://a").executeAsOneOrNull() shouldNotBe null
        queries.selectAll().executeAsList().size shouldBeEqualTo 1

        // INSERT OR REPLACE replaces the row for the same primary key.
        queries.upsert("https://a", "second", 2L)
        queries.selectAll().executeAsList().size shouldBeEqualTo 1

        queries.deleteByUrl("https://a")
        queries.selectByUrl("https://a").executeAsOneOrNull() shouldBe null
    }

    @Test
    fun `transactions commit atomically`() {
        val queries = database.rawCacheQueries

        database.transaction {
            queries.upsert("https://a", "1", 1L)
            queries.upsert("https://b", "2", 2L)
        }

        queries.selectAll().executeAsList().size shouldBeEqualTo 2
    }

    @Test
    fun `blob binding round-trips through the cursor factory`() {
        driver.execute(null, "CREATE TABLE blob_test (id INTEGER PRIMARY KEY, payload BLOB)", 0, null)
        val payload = byteArrayOf(0, 1, 2, 3, 127, -1, -128)

        driver.execute(null, "INSERT INTO blob_test (id, payload) VALUES (?, ?)", 2) {
            bindLong(0, 1L)
            bindBytes(1, payload)
        }

        val read = driver.executeQuery(
                identifier = null,
                sql = "SELECT payload FROM blob_test WHERE id = ?",
                mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getBytes(0) else null) },
                parameters = 1,
        ) { bindLong(0, 1L) }.value

        read shouldNotBe null
        read!!.toList() shouldBeEqualTo payload.toList()
    }

    @Test
    fun `asFlow reflects writes`() {
        runBlocking {
            val dispatcher = newDatabaseDispatcher("test-db")
            database.rawCacheQueries.upsert("https://a", "1", 1L)

            val rows = database.rawCacheQueries.selectAll()
                    .asFlow()
                    .mapToList(dispatcher)
                    .first()

            rows.size shouldBeEqualTo 1
        }
    }
}
