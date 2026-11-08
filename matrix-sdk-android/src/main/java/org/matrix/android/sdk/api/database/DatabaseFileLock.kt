/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.database

import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import java.io.File

object DatabaseFileLock {
    /**
     * Runs [block] while the SDK cannot write to the database at [databaseFile], so the file and its `-wal`
     * can be copied consistently. Writers wait until [block] returns; keep it to the copy.
     */
    fun <T> withWritesBlocked(databaseFile: File, block: () -> T): T = FrameworkSqliteDriver.withWritesBlocked(databaseFile, block)
}
