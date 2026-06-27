/*
 * Copyright 2023 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.room.delete

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.session.room.model.localecho.RoomLocalEcho
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.room.delete.DeleteLocalRoomTask.Params
import org.matrix.android.sdk.internal.task.Task
import timber.log.Timber
import javax.inject.Inject

internal interface DeleteLocalRoomTask : Task<Params, Unit> {
    data class Params(val roomId: String)
}

internal class DefaultDeleteLocalRoomTask @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
) : DeleteLocalRoomTask {

    override suspend fun execute(params: Params) {
        val roomId = params.roomId
        if (!RoomLocalEcho.isLocalEchoId(roomId)) {
            Timber.i("## DeleteLocalRoomTask - Failed to remove room with id $roomId: not a local room")
            return
        }
        Timber.i("## DeleteLocalRoomTask - delete local room id $roomId")
        database.awaitDbTransaction(dispatcher) {
            stores.readReceipt.deleteByRoom(roomId)
            stores.roomMember.deleteByRoom(roomId)
            stores.currentStateEvent.deleteByRoom(roomId)
            stores.event.deleteByRoom(roomId)
            stores.timelineEvent.deleteByRoom(roomId)
            stores.chunk.deleteByRoom(roomId)
            stores.roomSummary.delete(roomId)
            stores.room.delete(roomId)
            stores.localRoomSummary.delete(roomId)
        }
    }
}
