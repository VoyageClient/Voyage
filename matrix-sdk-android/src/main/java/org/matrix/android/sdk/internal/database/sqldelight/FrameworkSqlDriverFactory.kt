/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sqldelight

import android.content.Context
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import java.io.File

internal class FrameworkSqlDriverFactory(
        private val context: Context,
) : SqlDriverFactory {

    override fun create(schema: SqlSchema<QueryResult.Value<Unit>>, databaseName: String): SqlDriver {
        return FrameworkSqliteDriver.create(context, databaseName, schema)
    }

    override fun create(schema: SqlSchema<QueryResult.Value<Unit>>, databaseFile: File): SqlDriver {
        return FrameworkSqliteDriver.create(databaseFile, schema)
    }
}
