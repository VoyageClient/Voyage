/*
 * Copyright 2024 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.room.membership.joining

import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface KnockRoomTask : Task<KnockRoomTask.Params, Unit> {
    data class Params(
            val roomIdOrAlias: String,
            val reason: String?,
            val viaServers: List<String> = emptyList()
    )
}

internal class DefaultKnockRoomTask @Inject constructor(
        private val roomAPI: RoomAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
) : KnockRoomTask {

    override suspend fun execute(params: KnockRoomTask.Params) {
        val extraParams = mutableMapOf<String, Any>().apply {
            params.reason?.let { this["reason"] = it }
        }
        // The KNOCK membership is reflected through the sync, no need to wait for the room locally.
        executeRequest(globalErrorReceiver) {
            roomAPI.knock(
                    roomIdOrAlias = params.roomIdOrAlias,
                    viaServers = params.viaServers.take(3),
                    params = extraParams
            )
        }
    }
}
