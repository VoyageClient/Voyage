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

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataEvent
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.database.mapper.AccountDataMapper
import org.matrix.android.sdk.internal.database.model.UserAccountDataEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.di.SessionDatabase
import javax.inject.Inject
import org.matrix.android.sdk.internal.database.sql.User_account_data as UserAccountDataRow

internal class UserAccountDataDataSource @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val accountDataMapper: AccountDataMapper,
) {
    private val queries get() = database.userAccountDataQueries

    fun getAccountDataEvent(type: String): UserAccountDataEvent? {
        return getAccountDataEvents(setOf(type)).firstOrNull()
    }

    fun getAccountDataEventFlow(type: String): Flow<Optional<UserAccountDataEvent>> {
        return getAccountDataEventsFlow(setOf(type)).map { it.firstOrNull().toOptional() }
    }

    // LiveData views for the android-only internal consumers (IntegrationManager/WidgetManager etc.).
    fun getLiveAccountDataEvent(type: String): LiveData<Optional<UserAccountDataEvent>> =
            getAccountDataEventFlow(type).asLiveData()

    fun getLiveAccountDataEvents(types: Set<String>): LiveData<List<UserAccountDataEvent>> =
            getAccountDataEventsFlow(types).asLiveData()

    fun getAccountDataEvents(types: Set<String>): List<UserAccountDataEvent> {
        val rows = if (types.isEmpty()) queries.selectAll().executeAsList() else queries.selectByTypes(types).executeAsList()
        return rows.map { it.toEvent() }
    }

    fun getAccountDataEventsFlow(types: Set<String>): Flow<List<UserAccountDataEvent>> {
        val query = if (types.isEmpty()) queries.selectAll() else queries.selectByTypes(types)
        return query.asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toEvent() } }
    }

    fun getAccountDataEventsStartWith(type: String): List<UserAccountDataEvent> {
        return queries.selectStartingWith(type).executeAsList().map { it.toEvent() }
    }

    private fun UserAccountDataRow.toEvent(): UserAccountDataEvent =
            accountDataMapper.map(UserAccountDataEntity(type = type, contentStr = content_str))
}
