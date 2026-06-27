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

import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.session.sync.model.accountdata.BreadcrumbsContent
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

// Use the same arbitrary value than Riot-Web
private const val MAX_BREADCRUMBS_ROOMS_NUMBER = 20

internal interface UpdateBreadcrumbsTask : Task<UpdateBreadcrumbsTask.Params, Unit> {
    data class Params(
            val newTopRoomId: String
    )
}

internal class DefaultUpdateBreadcrumbsTask @Inject constructor(
        private val saveBreadcrumbsTask: SaveBreadcrumbsTask,
        private val updateUserAccountDataTask: UpdateUserAccountDataTask,
        private val stores: SessionStores,
) : UpdateBreadcrumbsTask {

    override suspend fun execute(params: UpdateBreadcrumbsTask.Params) {
        val newBreadcrumbs = stores.breadcrumbs.get()
                .toMutableList()
                .apply {
                    // Move the newTopRoomId to first position.
                    remove(params.newTopRoomId)
                    add(0, params.newTopRoomId)
                }
                .take(MAX_BREADCRUMBS_ROOMS_NUMBER)
                .ifEmpty { listOf(params.newTopRoomId) }
        // Update the DB locally, do not wait for the sync
        saveBreadcrumbsTask.execute(SaveBreadcrumbsTask.Params(newBreadcrumbs))
        // And update account data
        updateUserAccountDataTask.execute(
                UpdateUserAccountDataTask.BreadcrumbsParams(
                        breadcrumbsContent = BreadcrumbsContent(newBreadcrumbs)
                )
        )
    }
}
