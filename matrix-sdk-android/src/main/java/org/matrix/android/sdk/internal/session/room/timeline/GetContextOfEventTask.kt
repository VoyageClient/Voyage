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

package org.matrix.android.sdk.internal.session.room.timeline

import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.filter.FilterRepository
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface GetContextOfEventTask : Task<GetContextOfEventTask.Params, TokenChunkEventPersistor.Result> {

    data class Params(
            val roomId: String,
            val eventId: String,
            // 0 = the target event alone (the default for jump-to-event, where merging must stay
            // trivial); gap recovery asks for a window so the recovered chunk holds real history.
            val limit: Int = 0,
    )
}

internal class DefaultGetContextOfEventTask @Inject constructor(
        private val roomAPI: RoomAPI,
        private val filterRepository: FilterRepository,
        private val tokenChunkEventPersistor: TokenChunkEventPersistor,
        private val globalErrorReceiver: GlobalErrorReceiver
) : GetContextOfEventTask {

    override suspend fun execute(params: GetContextOfEventTask.Params): TokenChunkEventPersistor.Result {
        val filter = filterRepository.getRoomFilterBody()
        val response = executeRequest(globalErrorReceiver) {
            roomAPI.getContextOfEvent(params.roomId, params.eventId, params.limit, filter)
        }
        return tokenChunkEventPersistor.insertInDb(response, params.roomId, PaginationDirection.FORWARDS)
    }
}
