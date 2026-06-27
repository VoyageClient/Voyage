/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.filter

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.internal.database.model.FilterEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import javax.inject.Inject

internal class DefaultFilterRepository @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
) : FilterRepository {

    override suspend fun storeSyncFilter(filter: Filter, filterId: String, roomEventFilter: RoomEventFilter) {
        database.awaitDbTransaction(dispatcher) {
            // We manage only one filter for now
            stores.filter.upsert(
                    FilterEntity(
                            filterBodyJson = filter.toJSONString(),
                            roomEventFilterJson = roomEventFilter.toJSONString(),
                            filterId = filterId,
                    )
            )
        }
    }

    override suspend fun getStoredSyncFilterBody(): String =
            database.awaitDbTransaction(dispatcher) { stores.filter.get()?.filterBodyJson.orEmpty() }

    override suspend fun getStoredSyncFilterId(): String? =
            database.awaitDbTransaction(dispatcher) { stores.filter.get()?.filterId?.takeIf { it.isNotBlank() } }

    override suspend fun getRoomFilterBody(): String =
            database.awaitDbTransaction(dispatcher) { stores.filter.get()?.roomEventFilterJson.orEmpty() }
}
