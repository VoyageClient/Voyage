/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import java.io.File

/**
 * Platform seam for opening SQLDelight databases: everything above this interface is
 * platform-neutral JVM code; only the bound implementation touches the platform's SQLite stack.
 *
 * Implementations create the schema on first open and drop-and-recreate on any version change
 * (migrations are out of scope).
 */
internal interface SqlDriverFactory {

    /** Open a database in the platform's default database location for global (non-session) stores. */
    fun create(schema: SqlSchema<QueryResult.Value<Unit>>, databaseName: String): SqlDriver

    /** Open a database at an explicit file path (e.g. a per-session directory, removed on logout). */
    fun create(schema: SqlSchema<QueryResult.Value<Unit>>, databaseFile: File): SqlDriver
}
