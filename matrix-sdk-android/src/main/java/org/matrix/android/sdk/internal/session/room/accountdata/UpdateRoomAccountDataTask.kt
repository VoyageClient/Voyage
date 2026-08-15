/*
 * Copyright 2021 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.room.accountdata

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.session.room.summary.SqlRoomSummaryUpdater
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface UpdateRoomAccountDataTask : Task<UpdateRoomAccountDataTask.Params, Unit> {

    data class Params(
            val roomId: String,
            val type: String,
            val content: JsonDict
    )
}

internal class DefaultUpdateRoomAccountDataTask @Inject constructor(
        private val roomApi: RoomAPI,
        @UserId private val userId: String,
        private val globalErrorReceiver: GlobalErrorReceiver,
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val roomSummaryUpdater: SqlRoomSummaryUpdater,
) : UpdateRoomAccountDataTask {

    override suspend fun execute(params: UpdateRoomAccountDataTask.Params) {
        executeRequest(globalErrorReceiver) {
            roomApi.setRoomAccountData(userId, params.roomId, params.type, params.content)
        }
        // Local echo: persist immediately rather than waiting for the resulting sync.
        database.awaitDbTransaction(dispatcher) {
            stores.accountData.upsertRoomAccountData(params.roomId, params.type, ContentMapper.map(params.content))
            if (params.type in RoomStateOverrides.ALL_TYPES) {
                roomSummaryUpdater.refreshDisplay(stores, params.roomId)
            }
        }
    }
}
