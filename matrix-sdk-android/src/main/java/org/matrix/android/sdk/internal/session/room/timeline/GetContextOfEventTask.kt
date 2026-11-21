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

import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.debug.NetworkDiagnostics
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
    )
}

internal class DefaultGetContextOfEventTask @Inject constructor(
        private val roomAPI: RoomAPI,
        private val filterRepository: FilterRepository,
        private val tokenChunkEventPersistor: TokenChunkEventPersistor,
        private val globalErrorReceiver: GlobalErrorReceiver,
        private val localEchoRepository: dagger.Lazy<org.matrix.android.sdk.internal.session.room.send.LocalEchoRepository>,
) : GetContextOfEventTask {

    override suspend fun execute(params: GetContextOfEventTask.Params): TokenChunkEventPersistor.Result = NetworkDiagnostics.trace("context.load", params.roomId) {
        val filter = filterRepository.getRoomFilterBody()
        val eventId = localEchoRepository.get().resolveRemoteId(params.eventId) ?: params.eventId
        val response = NetworkDiagnostics.trace("context.fetch", params.roomId) {
            executeRequest(globalErrorReceiver) {
                roomAPI.getContextOfEvent(params.roomId, eventId, 0, filter)
            }
        }
        DebugLog.i {
            "NETDBG context received room=${NetworkDiagnostics.key(params.roomId)} events=${response.events.size} state=${response.stateEvents.orEmpty().size} " +
                    "stateTypesSampleMax256=${response.stateEvents.orEmpty().asSequence().take(256).groupingBy { it.type }.eachCount().entries.sortedByDescending { it.value }.take(8)} " +
                    "hasBefore=${response.start != null} hasAfter=${response.end != null}"
        }
        NetworkDiagnostics.trace("context.persist", params.roomId) {
            tokenChunkEventPersistor.insertInDb(response, params.roomId, PaginationDirection.FORWARDS)
        }
    }
}
