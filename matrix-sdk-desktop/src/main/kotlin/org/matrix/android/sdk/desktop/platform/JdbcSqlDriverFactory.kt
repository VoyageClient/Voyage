/*
 * Copyright 2024 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.desktop.platform

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.matrix.android.sdk.internal.database.sqldelight.SqlDriverFactory
import java.io.File

/**
 * Desktop [SqlDriverFactory]: a JDBC sqlite driver per database file under [dataDir]. Unlike the
 * android FrameworkSqliteDriver, the JDBC driver does not create/migrate the schema itself, so we
 * track the schema version in the sqlite `user_version` pragma and create/migrate on open.
 */
internal class JdbcSqlDriverFactory(private val dataDir: File) : SqlDriverFactory {

    override fun create(schema: SqlSchema<QueryResult.Value<Unit>>, databaseName: String): SqlDriver {
        return create(schema, File(dataDir, databaseName))
    }

    override fun create(schema: SqlSchema<QueryResult.Value<Unit>>, databaseFile: File): SqlDriver {
        databaseFile.parentFile?.mkdirs()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}")
        val current = driver.userVersion()
        when {
            current == 0L -> {
                schema.create(driver)
                driver.setUserVersion(schema.version)
            }
            current < schema.version -> {
                schema.migrate(driver, current, schema.version)
                driver.setUserVersion(schema.version)
            }
        }
        return driver
    }

    private fun SqlDriver.userVersion(): Long =
            executeQuery(null, "PRAGMA user_version", { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else 0L)
            }, 0).value ?: 0L

    private fun SqlDriver.setUserVersion(version: Long) {
        execute(null, "PRAGMA user_version = $version", 0)
    }
}
