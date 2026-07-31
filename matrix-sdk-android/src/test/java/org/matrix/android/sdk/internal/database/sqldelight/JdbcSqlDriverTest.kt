/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sqldelight

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.android.sdk.internal.auth.db.AuthSqlDatabase
import org.matrix.android.sdk.internal.crypto.store.db.sql.CryptoSqlDatabase
import org.matrix.android.sdk.internal.database.global.GlobalSqlDatabase
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.session.contentscanner.db.ContentScannerSqlDatabase
import org.matrix.android.sdk.internal.session.identity.db.IdentitySqlDatabase
import org.matrix.android.sdk.internal.session.search.index.db.EventIndexSqlDatabase

/**
 * Desktop-portability proof: every SQLDelight schema and the generated query code must run on a
 * plain-JVM sqlite driver (no Android at runtime), since the desktop app will use exactly this
 * driver in place of FrameworkSqliteDriver.
 */
class JdbcSqlDriverTest {

    @Test
    fun `all schemas create on a plain JVM sqlite driver`() {
        listOf(
                GlobalSqlDatabase.Schema,
                AuthSqlDatabase.Schema,
                IdentitySqlDatabase.Schema,
                ContentScannerSqlDatabase.Schema,
                CryptoSqlDatabase.Schema,
                SessionSqlDatabase.Schema,
                EventIndexSqlDatabase.Schema,
        ).forEach { schema ->
            withInMemoryDriver { driver -> schema.create(driver) }
        }
    }

    @Test
    fun `auth database round-trips through generated queries on the JVM driver`() {
        withInMemoryDriver { driver ->
            AuthSqlDatabase.Schema.create(driver)
            val queries = AuthSqlDatabase(driver).sessionParamsQueries

            queries.upsert(
                    session_id = "@alice:example.org|DEVICE",
                    user_id = "@alice:example.org",
                    credentials_json = "{}",
                    home_server_connection_config_json = "{}",
                    is_token_valid = 1L,
                    login_type = "password",
            )

            val stored = queries.selectAll().executeAsList()
            assertEquals(1, stored.size)
            assertEquals("@alice:example.org", stored.single().user_id)

            queries.setTokenInvalid("@alice:example.org|DEVICE")
            assertEquals(0L, queries.selectById("@alice:example.org|DEVICE").executeAsOne().is_token_valid)
        }
    }

    private fun withInMemoryDriver(block: (SqlDriver) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            block(driver)
        } finally {
            driver.close()
        }
    }
}
