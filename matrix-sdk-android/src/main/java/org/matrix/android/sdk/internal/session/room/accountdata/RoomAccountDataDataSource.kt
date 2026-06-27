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

package org.matrix.android.sdk.internal.session.room.accountdata

import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.session.room.accountdata.RoomAccountDataEvent
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.database.mapper.AccountDataMapper
import org.matrix.android.sdk.internal.database.model.RoomAccountDataEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sqldelight.asLiveList
import org.matrix.android.sdk.internal.di.SessionDatabase
import javax.inject.Inject
import org.matrix.android.sdk.internal.database.sql.Room_account_data as RoomAccountDataRow

internal class RoomAccountDataDataSource @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val accountDataMapper: AccountDataMapper,
) {
    private val queries get() = database.roomAccountDataQueries

    fun getAccountDataEvent(roomId: String, type: String): RoomAccountDataEvent? {
        return getAccountDataEvents(roomId, setOf(type)).firstOrNull()
    }

    fun getLiveAccountDataEvent(roomId: String, type: String): LiveData<Optional<RoomAccountDataEvent>> {
        return getLiveAccountDataEvents(roomId, setOf(type)).map { it.firstOrNull().toOptional() }
    }

    fun getAccountDataEvents(roomId: String?, types: Set<String>): List<RoomAccountDataEvent> {
        val rows = if (roomId != null) queries.selectByRoom(roomId).executeAsList() else queries.selectAll().executeAsList()
        return rows.filter { types.isEmpty() || it.type in types }.map { it.toEvent() }
    }

    fun getLiveAccountDataEvents(roomId: String?, types: Set<String>): LiveData<List<RoomAccountDataEvent>> {
        val query = if (roomId != null) queries.selectByRoom(roomId) else queries.selectAll()
        return query.asLiveList(dispatcher).map { rows ->
            rows.filter { types.isEmpty() || it.type in types }.map { it.toEvent() }
        }
    }

    private fun RoomAccountDataRow.toEvent(): RoomAccountDataEvent =
            accountDataMapper.map(room_id, RoomAccountDataEntity(type = type, contentStr = content_str))
}
