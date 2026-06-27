/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.test.fakes

import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.matrix.android.sdk.internal.database.sqldelight.newDatabaseDispatcher
import org.robolectric.RuntimeEnvironment

/**
 * A real, in-memory session SQLDelight database for tests of stores/tasks that moved off Realm/Monarchy.
 * Backed by the framework-SQLite driver over Robolectric's SQLite, so it exercises the actual `.sq`
 * queries. Requires the test to run with the RobolectricTestRunner (for the application context).
 */
internal class FakeSessionDatabase {

    val driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = SessionSqlDatabase.Schema)
    val database = SessionSqlDatabase(driver)
    val stores = SessionStores(database)
    val dispatcher = newDatabaseDispatcher("test-session-db")

    fun close() = driver.close()
}
