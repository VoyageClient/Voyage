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

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.matrix.android.sdk.api.session.profile.ProfileOverrides
import org.matrix.android.sdk.api.session.user.model.User
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.SessionDatabase
import javax.inject.Inject
import org.matrix.android.sdk.internal.database.sql.User as UserRow

internal class UserDataSource @Inject constructor(
        @SessionDatabase internal val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
) {

    internal fun rowToUser(row: UserRow): User = overriddenUser(row.user_id, row.display_name, row.avatar_url)

    fun getUser(userId: String): User? = stores.user.getUser(userId)?.let { overriddenUser(it.userId, it.displayName, it.avatarUrl) }

    fun getUserOrDefault(userId: String): User = getUser(userId) ?: overriddenUser(userId, null, null)

    private fun overriddenUser(userId: String, displayName: String?, avatarUrl: String?) = User(
            userId,
            ProfileOverrides.displayNameFor(userId) ?: displayName,
            ProfileOverrides.avatarUrlFor(userId) ?: avatarUrl,
    )

    fun getUserFlow(userId: String): Flow<Optional<User>> {
        return database.userQueries.selectByUserId(userId)
                .asFlow().mapToList(dispatcher)
                .map { rows -> rows.firstOrNull()?.toUser().toOptional() }
    }

    fun getUsersFlow(): Flow<List<User>> {
        return database.userQueries.selectAll()
                .asFlow().mapToList(dispatcher)
                .map { rows -> rows.map { it.toUser() } }
    }

    fun getIgnoredUsersFlow(): Flow<List<User>> {
        return database.ignoredUserQueries.selectAll()
                .asFlow().mapToList(dispatcher)
                // Skip any malformed blank id: User("") fails MatrixItem's @-prefix check, which would
                // crash the ignored-users list (or drop the whole list) rather than just that one entry.
                .map { ids -> ids.filter { it.isNotBlank() }.map { getUser(it) ?: User(userId = it) } }
    }

    fun getIgnoredUserIds(): List<String> = database.ignoredUserQueries.selectAll().executeAsList()

    private fun UserRow.toUser() = rowToUser(this)
}
