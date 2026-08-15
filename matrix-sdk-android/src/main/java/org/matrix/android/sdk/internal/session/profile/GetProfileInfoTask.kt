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

package org.matrix.android.sdk.internal.session.profile

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.session.profile.ProfileOverrides
import org.matrix.android.sdk.api.session.user.model.User
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.user.UserEntityFactory
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal abstract class GetProfileInfoTask : Task<GetProfileInfoTask.Params, JsonDict> {
    data class Params(
            val userId: String,
            val storeInDatabase: Boolean = true,
    )
}

internal class DefaultGetProfileInfoTask @Inject constructor(
        private val profileAPI: ProfileAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
) : GetProfileInfoTask() {

    override suspend fun execute(params: Params): JsonDict {
        val profile = executeRequest(globalErrorReceiver) {
            profileAPI.getProfile(params.userId)
        }.also { user ->
            if (params.storeInDatabase) {
                database.awaitDbTransaction(dispatcher) {
                    stores.user.upsertUser(UserEntityFactory.create(User.fromJson(params.userId, user)))
                }
            }
        }
        // Merge client-side overrides over the profile: any overridden field replaces (or adds to)
        // the server value; a null override removes the field.
        val overrides = ProfileOverrides.fieldsFor(params.userId) ?: return profile
        val merged = profile.toMutableMap()
        overrides.forEach { (key, value) ->
            if (value == null) merged.remove(key) else merged[key] = value
        }
        return merged
    }
}
