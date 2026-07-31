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

import kotlinx.coroutines.sync.withLock
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.sync.model.accountdata.IgnoredUsersContent
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface UpdateIgnoredUserIdsTask : Task<UpdateIgnoredUserIdsTask.Params, Unit> {

    data class Params(
            val userIdsToIgnore: List<String> = emptyList(),
            val userIdsToUnIgnore: List<String> = emptyList()
    )
}

internal class DefaultUpdateIgnoredUserIdsTask @Inject constructor(
        private val accountDataApi: AccountDataAPI,
        private val stores: SessionStores,
        @UserId private val userId: String,
        private val globalErrorReceiver: GlobalErrorReceiver,
        private val ignoredUsersUpdater: IgnoredUsersUpdater,
) : UpdateIgnoredUserIdsTask {

    override suspend fun execute(params: UpdateIgnoredUserIdsTask.Params) {
        // Serialize with the last-pushed set as the base (not the not-yet-synced DB), so back-to-back
        // (un)ignores don't each read the stale list and undo one another.
        ignoredUsersUpdater.mutex.withLock {
            val original = ignoredUsersUpdater.lastKnownIds ?: stores.user.getIgnoredUserIds().toSet()
            val ignoredUserIds = original.toMutableSet()
            ignoredUserIds.removeAll { it in params.userIdsToUnIgnore }
            ignoredUserIds.addAll(params.userIdsToIgnore)
            // Never persist a blank id: a stray "" corrupts m.ignored_user_list (crashing / blanking the
            // ignored-users screen), and dropping it here self-heals an already-corrupt list on next write.
            ignoredUserIds.removeAll { it.isBlank() }
            if (original == ignoredUserIds) {
                // No change (record the base so the next update reuses it)
                ignoredUsersUpdater.lastKnownIds = ignoredUserIds
                return
            }
            val body = IgnoredUsersContent.createWithUserIds(ignoredUserIds.toList())
            executeRequest(globalErrorReceiver) {
                accountDataApi.setAccountData(userId, UserAccountDataTypes.TYPE_IGNORED_USER_LIST, body)
            }
            ignoredUsersUpdater.lastKnownIds = ignoredUserIds
        }
    }
}
