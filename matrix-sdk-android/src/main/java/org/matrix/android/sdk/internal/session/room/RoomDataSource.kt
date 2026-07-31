/*
 * Copyright 2022 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.room

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.internal.database.model.RoomMembersLoadStatusType
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.di.SessionDatabase
import javax.inject.Inject

internal class RoomDataSource @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
) {
    fun getRoomMembersLoadStatus(roomId: String): RoomMembersLoadStatusType {
        return database.roomQueries.selectByRoomId(roomId).executeAsOneOrNull()
                ?.let { RoomMembersLoadStatusType.valueOf(it.members_load_status_str) }
                ?: RoomMembersLoadStatusType.NONE
    }

    fun getRoomMembersLoadStatusFlow(roomId: String): Flow<Boolean> {
        return database.roomQueries.selectByRoomId(roomId)
                .asFlow()
                .mapToList(dispatcher)
                .map { rows -> rows.firstOrNull()?.members_load_status_str == RoomMembersLoadStatusType.LOADED.name }
    }
}
