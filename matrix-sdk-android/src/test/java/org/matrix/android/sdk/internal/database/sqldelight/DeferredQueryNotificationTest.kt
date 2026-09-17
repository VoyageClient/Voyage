/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sqldelight

import app.cash.sqldelight.Query
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DeferredQueryNotificationTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var database: SessionSqlDatabase
    private var notifications = 0
    private val listener = Query.Listener { notifications++ }

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = SessionSqlDatabase.Schema)
        database = SessionSqlDatabase(driver)
        driver.addListener("watched", listener = listener)
    }

    @After
    fun tearDown() {
        driver.removeListener("watched", listener = listener)
        driver.close()
    }

    @Test
    fun `outside a transaction a write notifies immediately`() {
        driver.notifyListeners("watched")

        notifications shouldBeEqualTo 1
    }

    @Test
    fun `inside a transaction nothing is notified until it commits`() {
        database.transaction {
            driver.notifyListeners("watched")
            notifications shouldBeEqualTo 0
        }

        notifications shouldBeEqualTo 1
    }

    @Test
    fun `many writes in one transaction wake a listener once`() {
        database.transaction {
            repeat(400) { driver.notifyListeners("watched") }
        }

        notifications shouldBeEqualTo 1
    }

    @Test
    fun `a rolled back transaction notifies nothing`() {
        runCatching {
            database.transaction {
                driver.notifyListeners("watched")
                error("rolled back")
            }
        }

        notifications shouldBeEqualTo 0
    }

    @Test
    fun `a nested transaction's writes are notified when the outer one commits`() {
        database.transaction {
            database.transaction {
                driver.notifyListeners("watched")
            }
            notifications shouldBeEqualTo 0
        }

        notifications shouldBeEqualTo 1
    }

    @Test
    fun `an unwatched key notifies nobody`() {
        database.transaction {
            driver.notifyListeners("other")
        }

        notifications shouldBeEqualTo 0
    }
}
