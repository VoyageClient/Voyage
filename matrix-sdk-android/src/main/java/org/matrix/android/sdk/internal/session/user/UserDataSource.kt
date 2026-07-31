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

package org.matrix.android.sdk.internal.session.user

import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import androidx.paging.PagedList
import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.session.user.model.User
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.asLiveList
import org.matrix.android.sdk.internal.database.sqldelight.livePaged
import org.matrix.android.sdk.internal.di.SessionDatabase
import javax.inject.Inject
import org.matrix.android.sdk.internal.database.sql.User as UserRow

internal class UserDataSource @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
) {

    fun getUser(userId: String): User? = stores.user.getUser(userId)?.let { User(it.userId, it.displayName, it.avatarUrl) }

    fun getUserOrDefault(userId: String): User = getUser(userId) ?: User(userId)

    fun getUserLive(userId: String): LiveData<Optional<User>> {
        return database.userQueries.selectByUserId(userId)
                .asLiveList(dispatcher)
                .map { rows -> rows.firstOrNull()?.toUser().toOptional() }
    }

    fun getUsersLive(): LiveData<List<User>> {
        return database.userQueries.selectAll()
                .asLiveList(dispatcher)
                .map { rows -> rows.map { it.toUser() } }
    }

    fun getPagedUsersLive(filter: String?, excludedUserIds: Set<String>?): LiveData<PagedList<User>> {
        val query = if (filter.isNullOrEmpty()) {
            database.userQueries.selectAll()
        } else {
            database.userQueries.searchByDisplayName(filter, filter)
        }
        return livePaged(query, pageSize = 100) {
            query.executeAsList()
                    .filter { excludedUserIds.isNullOrEmpty() || it.user_id !in excludedUserIds }
                    .map { it.toUser() }
        }
    }

    fun getIgnoredUsersLive(): LiveData<List<User>> {
        return database.ignoredUserQueries.selectAll()
                .asLiveList(dispatcher)
                // Skip any malformed blank id: User("") fails MatrixItem's @-prefix check, which would
                // crash the ignored-users list (or drop the whole list) rather than just that one entry.
                .map { ids -> ids.filter { it.isNotBlank() }.map { getUser(it) ?: User(userId = it) } }
    }

    fun getIgnoredUserIds(): List<String> = database.ignoredUserQueries.selectAll().executeAsList()

    private fun UserRow.toUser() = User(user_id, display_name, avatar_url)
}
