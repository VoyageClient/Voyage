/*
 * Copyright 2021 The Matrix.org Foundation C.I.C.
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
import kotlinx.coroutines.flow.Flow
import org.matrix.android.sdk.api.session.accountdata.SessionAccountDataService
import org.matrix.android.sdk.api.session.accountdata.StealthAccountData
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataEvent
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.profile.ProfileOverrides
import org.matrix.android.sdk.api.session.room.accountdata.RoomAccountDataEvent
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.awaitCallback
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.profile.ProfileOverridesUpdater
import org.matrix.android.sdk.internal.session.room.accountdata.RoomAccountDataDataSource
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.matrix.android.sdk.internal.task.configureWith
import javax.inject.Inject

internal class DefaultSessionAccountDataService @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val updateUserAccountDataTask: UpdateUserAccountDataTask,
        private val deleteUserAccountDataTask: DeleteUserAccountDataTask,
        private val userAccountDataDataSource: UserAccountDataDataSource,
        private val roomAccountDataDataSource: RoomAccountDataDataSource,
        private val taskExecutor: TaskExecutor,
        private val profileOverridesUpdater: ProfileOverridesUpdater,
) : SessionAccountDataService {

    override fun getUserAccountDataEvent(type: String): UserAccountDataEvent? =
            userAccountDataDataSource.getAccountDataEvent(type)

    override fun getUserAccountDataEventFlow(type: String): Flow<Optional<UserAccountDataEvent>> =
            userAccountDataDataSource.getAccountDataEventFlow(type)

    override fun getUserAccountDataEvents(types: Set<String>): List<UserAccountDataEvent> =
            userAccountDataDataSource.getAccountDataEvents(types)

    override fun getUserAccountDataEventsFlow(types: Set<String>): Flow<List<UserAccountDataEvent>> =
            userAccountDataDataSource.getAccountDataEventsFlow(types)

    override fun getRoomAccountDataEvents(types: Set<String>): List<RoomAccountDataEvent> =
            roomAccountDataDataSource.getAccountDataEvents(null, types)

    override fun getRoomAccountDataEventsFlow(types: Set<String>): Flow<List<RoomAccountDataEvent>> =
            roomAccountDataDataSource.getAccountDataEventsFlow(null, types)

    override suspend fun updateUserAccountData(type: String, content: Content) {
        if (!StealthAccountData.isLocalOnly(type)) {
            val params = UpdateUserAccountDataTask.AnyParams(type = type, any = content)
            awaitCallback { callback ->
                updateUserAccountDataTask.configureWith(params) {
                    this.retryCount = 5
                    this.callback = callback
                }
                        .executeBy(taskExecutor)
            }
        }
        // Local echo: persist immediately rather than waiting for the resulting sync
        // (and, under stealth mode, this local write is the only place it is ever stored).
        database.awaitDbTransaction(dispatcher) {
            if (content.isNullOrEmpty()) {
                stores.accountData.deleteUserAccountData(type)
            } else {
                stores.accountData.upsertUserAccountData(type, ContentMapper.map(content))
            }
        }
        if (ProfileOverrides.isAccountDataType(type)) {
            // Off the caller's (usually main) thread: applying rewrites affected room summaries.
            database.awaitDbTransaction(dispatcher) {
                profileOverridesUpdater.apply()
            }
        }
    }

    override fun getUserAccountDataEventsStartWith(type: String): List<UserAccountDataEvent> =
            userAccountDataDataSource.getAccountDataEventsStartWith(type)

    override suspend fun deleteUserAccountData(type: String) {
        if (StealthAccountData.isLocalOnly(type)) {
            database.awaitDbTransaction(dispatcher) {
                stores.accountData.deleteUserAccountData(type)
            }
            return
        }
        deleteUserAccountDataTask.execute(DeleteUserAccountDataTask.Params(type))
    }
}
