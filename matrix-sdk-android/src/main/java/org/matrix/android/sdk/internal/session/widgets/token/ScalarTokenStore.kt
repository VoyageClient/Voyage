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

package org.matrix.android.sdk.internal.session.widgets.token

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import javax.inject.Inject

internal class ScalarTokenStore @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
) {

    fun getToken(apiUrl: String): String? = stores.integrationManager.getScalarToken(apiUrl)

    suspend fun setToken(apiUrl: String, token: String) {
        database.awaitDbTransaction(dispatcher) { stores.integrationManager.upsertScalarToken(apiUrl, token) }
    }

    suspend fun clearToken(apiUrl: String) {
        database.awaitDbTransaction(dispatcher) { stores.integrationManager.deleteScalarToken(apiUrl) }
    }
}
