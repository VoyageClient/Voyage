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
package org.matrix.android.sdk.internal.session.user.accountdata

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

/**
 * Save the Breadcrumbs roomId list in DB, either from the sync, or updated locally.
 */
internal interface SaveBreadcrumbsTask : Task<SaveBreadcrumbsTask.Params, Unit> {
    data class Params(
            val recentRoomIds: List<String>
    )
}

internal class DefaultSaveBreadcrumbsTask @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
) : SaveBreadcrumbsTask {

    override suspend fun execute(params: SaveBreadcrumbsTask.Params) {
        database.awaitDbTransaction(dispatcher) {
            stores.breadcrumbs.set(params.recentRoomIds)
            stores.roomSummary.resetBreadcrumbsIndex()
            params.recentRoomIds.forEachIndexed { index, roomId -> stores.roomSummary.updateBreadcrumbsIndex(roomId, index) }
        }
    }
}
