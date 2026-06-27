/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.raw

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.internal.database.global.GlobalSqlDatabase
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.GlobalDatabase
import javax.inject.Inject

internal class RawCacheStore @Inject constructor(
        @GlobalDatabase private val database: GlobalSqlDatabase,
        @GlobalDatabase private val dispatcher: CoroutineDispatcher,
) {
    data class Entry(val data: String, val lastUpdatedTimestamp: Long)

    suspend fun get(url: String): Entry? = withContext(dispatcher) {
        database.rawCacheQueries.selectByUrl(url) { _, data, lastUpdatedTimestamp -> Entry(data, lastUpdatedTimestamp) }
                .executeAsOneOrNull()
    }

    suspend fun put(url: String, data: String, lastUpdatedTimestamp: Long) {
        database.awaitDbTransaction(dispatcher) {
            database.rawCacheQueries.upsert(url, data, lastUpdatedTimestamp)
        }
    }

    suspend fun clear() {
        database.awaitDbTransaction(dispatcher) {
            database.rawCacheQueries.deleteAll()
        }
    }
}
