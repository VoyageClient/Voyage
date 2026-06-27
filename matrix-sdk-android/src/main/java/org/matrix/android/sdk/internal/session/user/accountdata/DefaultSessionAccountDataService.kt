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

import androidx.lifecycle.LiveData
import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.session.accountdata.SessionAccountDataService
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataEvent
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.room.accountdata.RoomAccountDataEvent
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.room.accountdata.RoomAccountDataDataSource
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.matrix.android.sdk.internal.task.configureWith
import org.matrix.android.sdk.api.util.awaitCallback
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
) : SessionAccountDataService {

    override fun getUserAccountDataEvent(type: String): UserAccountDataEvent? =
            userAccountDataDataSource.getAccountDataEvent(type)

    override fun getLiveUserAccountDataEvent(type: String): LiveData<Optional<UserAccountDataEvent>> =
            userAccountDataDataSource.getLiveAccountDataEvent(type)

    override fun getUserAccountDataEvents(types: Set<String>): List<UserAccountDataEvent> =
            userAccountDataDataSource.getAccountDataEvents(types)

    override fun getLiveUserAccountDataEvents(types: Set<String>): LiveData<List<UserAccountDataEvent>> =
            userAccountDataDataSource.getLiveAccountDataEvents(types)

    override fun getRoomAccountDataEvents(types: Set<String>): List<RoomAccountDataEvent> =
            roomAccountDataDataSource.getAccountDataEvents(null, types)

    override fun getLiveRoomAccountDataEvents(types: Set<String>): LiveData<List<RoomAccountDataEvent>> =
            roomAccountDataDataSource.getLiveAccountDataEvents(null, types)

    override suspend fun updateUserAccountData(type: String, content: Content) {
        val params = UpdateUserAccountDataTask.AnyParams(type = type, any = content)
        awaitCallback { callback ->
            updateUserAccountDataTask.configureWith(params) {
                this.retryCount = 5
                this.callback = callback
            }
                    .executeBy(taskExecutor)
        }
        // Local echo: persist immediately rather than waiting for the resulting sync.
        database.awaitDbTransaction(dispatcher) {
            if (content.isNullOrEmpty()) {
                stores.accountData.deleteUserAccountData(type)
            } else {
                stores.accountData.upsertUserAccountData(type, ContentMapper.map(content))
            }
        }
    }

    override fun getUserAccountDataEventsStartWith(type: String): List<UserAccountDataEvent> =
            userAccountDataDataSource.getAccountDataEventsStartWith(type)

    override suspend fun deleteUserAccountData(type: String) {
        deleteUserAccountDataTask.execute(DeleteUserAccountDataTask.Params(type))
    }
}
