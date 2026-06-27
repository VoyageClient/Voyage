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

package org.matrix.android.sdk.internal.session.widgets

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sqldelight.awaitNotEmptyResult
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.task.Task
import timber.log.Timber
import javax.inject.Inject

internal interface CreateWidgetTask : Task<CreateWidgetTask.Params, String> {
    data class Params(
            val roomId: String,
            val widgetId: String,
            val content: Content
    )
}

internal class DefaultCreateWidgetTask @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val roomAPI: RoomAPI,
        @UserId private val userId: String,
        private val globalErrorReceiver: GlobalErrorReceiver,
) : CreateWidgetTask {

    override suspend fun execute(params: CreateWidgetTask.Params): String {
        val response = executeRequest(globalErrorReceiver) {
            roomAPI.sendStateEvent(
                    roomId = params.roomId,
                    stateEventType = EventType.STATE_ROOM_WIDGET_LEGACY,
                    stateKey = params.widgetId,
                    params = params.content
            )
        }
        awaitNotEmptyResult(
                database.currentStateEventQueries.selectOne(params.roomId, EventType.STATE_ROOM_WIDGET_LEGACY, params.widgetId),
                30_000L,
                dispatcher,
        )
        return response.eventId.also {
            Timber.d("Widget state event: $it just sent in room ${params.roomId}")
        }
    }
}
