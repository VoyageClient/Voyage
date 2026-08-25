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

import kotlinx.coroutines.flow.Flow
import org.matrix.android.sdk.api.session.profile.ProfileOverrides
import org.matrix.android.sdk.api.session.user.UserService
import org.matrix.android.sdk.api.session.user.model.User
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.internal.session.profile.GetProfileInfoTask
import org.matrix.android.sdk.internal.session.user.accountdata.UnIgnoredContentRecoverer
import org.matrix.android.sdk.internal.session.user.accountdata.UpdateIgnoredUserIdsTask
import org.matrix.android.sdk.internal.session.user.model.ReportUserTask
import org.matrix.android.sdk.internal.session.user.model.SearchUserTask
import javax.inject.Inject

internal class DefaultUserService @Inject constructor(
        private val userDataSource: UserDataSource,
        private val searchUserTask: SearchUserTask,
        private val updateIgnoredUserIdsTask: UpdateIgnoredUserIdsTask,
        private val reportUserTask: ReportUserTask,
        private val getProfileInfoTask: GetProfileInfoTask,
        private val unIgnoredContentRecoverer: UnIgnoredContentRecoverer,
) : UserService {

    override fun getUser(userId: String): User? {
        return userDataSource.getUser(userId)
    }

    override suspend fun resolveUser(userId: String): User {
        return getUser(userId) ?: run {
            val params = GetProfileInfoTask.Params(userId)
            val json = ProfileOverrides.mergedOver(userId, getProfileInfoTask.execute(params))
            User.fromJson(userId, json)
        }
    }

    override fun getUserFlow(userId: String): Flow<Optional<User>> {
        return userDataSource.getUserFlow(userId)
    }

    override fun getUsersFlow(): Flow<List<User>> {
        return userDataSource.getUsersFlow()
    }

    override fun getIgnoredUsersFlow(): Flow<List<User>> {
        return userDataSource.getIgnoredUsersFlow()
    }

    override fun getIgnoredUserIds(): List<String> {
        return userDataSource.getIgnoredUserIds()
    }

    override suspend fun searchUsersDirectory(
            search: String,
            limit: Int,
            excludedUserIds: Set<String>
    ): List<User> {
        val params = SearchUserTask.Params(limit, search, excludedUserIds)
        return searchUserTask.execute(params)
    }

    override suspend fun ignoreUserIds(userIds: List<String>) {
        val params = UpdateIgnoredUserIdsTask.Params(userIdsToIgnore = userIds.toList())
        updateIgnoredUserIdsTask.execute(params)
    }

    override suspend fun unIgnoreUserIds(userIds: List<String>) {
        val params = UpdateIgnoredUserIdsTask.Params(userIdsToUnIgnore = userIds.toList())
        updateIgnoredUserIdsTask.execute(params)
        // Applying the list locally only reveals what is already stored; what the server withheld while
        // they were ignored has to be fetched. Sync would get to it when the change echoes back, but a
        // sliding connection can sit on that echo, so start it here.
        unIgnoredContentRecoverer.recoverPending()
    }

    override suspend fun reportUser(userId: String, reason: String) {
        reportUserTask.execute(ReportUserTask.Params(userId, reason))
    }
}
