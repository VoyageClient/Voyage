/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.matrix.android.sdk.internal.session.sync.handler

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.user.model.User
import org.matrix.android.sdk.internal.crypto.crosssigning.UpdateTrustWorkerDataRepository
import org.matrix.android.sdk.internal.crypto.crosssigning.UpdateTrustWorkerParams
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.profile.GetProfileInfoTask
import org.matrix.android.sdk.internal.session.user.UserEntityFactory
import org.matrix.android.sdk.internal.util.logLimit
import org.matrix.android.sdk.internal.worker.BackgroundTaskBody
import org.matrix.android.sdk.internal.worker.BackgroundTaskContext
import org.matrix.android.sdk.internal.worker.BackgroundTaskOutcome
import timber.log.Timber
import javax.inject.Inject

/**
 * Note: We reuse the same type [UpdateTrustWorkerParams], since the input data are the same.
 */
internal class UpdateUserTaskBody @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val sessionDbDispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val updateTrustWorkerDataRepository: UpdateTrustWorkerDataRepository,
        private val getProfileInfoTask: GetProfileInfoTask,
) : BackgroundTaskBody<UpdateTrustWorkerParams> {

    override suspend fun execute(params: UpdateTrustWorkerParams, context: BackgroundTaskContext): BackgroundTaskOutcome {
        val userList = params.filename
                ?.let { updateTrustWorkerDataRepository.getParam(it) }
                ?.userIds
                ?: params.updatedUserIds.orEmpty()

        // List should not be empty, but let's avoid go further in case of empty list
        if (userList.isNotEmpty()) {
            Timber.v("## UpdateUserWorker - updating users: ${userList.logLimit()}")
            fetchAndUpdateUsers(userList)
        }

        cleanup(params)
        return BackgroundTaskOutcome.Success
    }

    private suspend fun fetchAndUpdateUsers(userIdsToFetch: Collection<String>) {
        fetchUsers(userIdsToFetch)
                .takeIf { it.isNotEmpty() }
                ?.saveLocally()
    }

    private suspend fun fetchUsers(userIdsToFetch: Collection<String>): List<User> {
        return userIdsToFetch.mapNotNull { userId ->
            tryOrNull {
                val profileJson = getProfileInfoTask.execute(GetProfileInfoTask.Params(
                        userId = userId,
                        // Bulk insert later, so tell the task not to store the User.
                        storeInDatabase = false,
                ))
                User.fromJson(userId, profileJson)
            }
        }
    }

    private suspend fun List<User>.saveLocally() {
        val userEntities = map { user -> UserEntityFactory.create(user) }
        Timber.d("## saveLocally()")
        database.awaitDbTransaction(sessionDbDispatcher) {
            Timber.d("## saveLocally() - in transaction")
            userEntities.forEach { stores.user.upsertUser(it) }
        }
        Timber.d("## saveLocally() - END")
    }

    private fun cleanup(params: UpdateTrustWorkerParams) {
        params.filename
                ?.let { updateTrustWorkerDataRepository.delete(it) }
    }
}
