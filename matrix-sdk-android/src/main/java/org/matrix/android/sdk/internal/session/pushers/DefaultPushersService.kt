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
package org.matrix.android.sdk.internal.session.pushers

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.matrix.android.sdk.api.session.pushers.HttpPusher
import org.matrix.android.sdk.api.session.pushers.Pusher
import org.matrix.android.sdk.api.session.pushers.PushersService
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.SessionId
import org.matrix.android.sdk.internal.platform.BackgroundTaskScheduler
import org.matrix.android.sdk.internal.platform.BackgroundTaskType
import org.matrix.android.sdk.internal.platform.backgroundTask
import org.matrix.android.sdk.internal.session.pushers.gateway.PushGatewayNotifyTask
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.matrix.android.sdk.internal.task.configureWith
import java.util.UUID
import javax.inject.Inject

internal class DefaultPushersService @Inject constructor(
        private val backgroundTaskScheduler: BackgroundTaskScheduler,
        @SessionDatabase private val database: org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase,
        @SessionDatabase private val dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        private val stores: org.matrix.android.sdk.internal.database.sql.store.SessionStores,
        @SessionId private val sessionId: String,
        private val getPusherTask: GetPushersTask,
        private val pushGatewayNotifyTask: PushGatewayNotifyTask,
        private val addPusherTask: AddPusherTask,
        private val togglePusherTask: TogglePusherTask,
        private val removePusherTask: RemovePusherTask,
        private val taskExecutor: TaskExecutor,
) : PushersService {

    override suspend fun testPush(
            url: String,
            appId: String,
            pushkey: String,
            eventId: String
    ) {
        pushGatewayNotifyTask.execute(PushGatewayNotifyTask.Params(url, appId, pushkey, eventId))
    }

    override fun refreshPushers() {
        getPusherTask
                .configureWith()
                .executeBy(taskExecutor)
    }

    override fun enqueueAddHttpPusher(httpPusher: HttpPusher): UUID {
        return enqueueAddPusher(httpPusher.toJsonPusher())
    }

    override suspend fun addHttpPusher(httpPusher: HttpPusher) {
        addPusherTask.execute(AddPusherTask.Params(httpPusher.toJsonPusher()))
    }

    private fun HttpPusher.toJsonPusher() = JsonPusher(
            pushKey = pushkey,
            kind = "http",
            appId = appId,
            profileTag = profileTag,
            lang = lang,
            appDisplayName = appDisplayName,
            deviceDisplayName = deviceDisplayName,
            data = JsonPusherData(url, EVENT_ID_ONLY.takeIf { withEventIdOnly }),
            append = append,
            enabled = enabled,
            deviceId = deviceId,
    )

    override suspend fun addEmailPusher(
            email: String,
            lang: String,
            emailBranding: String,
            appDisplayName: String,
            deviceDisplayName: String,
            append: Boolean
    ) {
        addPusherTask.execute(
                AddPusherTask.Params(
                        JsonPusher(
                                pushKey = email,
                                kind = Pusher.KIND_EMAIL,
                                appId = Pusher.APP_ID_EMAIL,
                                profileTag = "",
                                lang = lang,
                                appDisplayName = appDisplayName,
                                deviceDisplayName = deviceDisplayName,
                                data = JsonPusherData(brand = emailBranding),
                                append = append
                        )
                )
        )
    }

    override suspend fun togglePusher(pusher: Pusher, enable: Boolean) {
        togglePusherTask.execute(TogglePusherTask.Params(pusher.toJsonPusher(), enable))
    }

    private fun Pusher.toJsonPusher() = JsonPusher(
            pushKey = pushKey,
            kind = kind,
            appId = appId,
            appDisplayName = appDisplayName,
            deviceDisplayName = deviceDisplayName,
            profileTag = profileTag,
            lang = lang,
            data = JsonPusherData(data.url, data.format),
            append = false,
            enabled = enabled,
            deviceId = deviceId,
    )

    private fun enqueueAddPusher(pusher: JsonPusher): UUID {
        val params = AddPusherWorker.Params(sessionId, pusher)
        return backgroundTaskScheduler.enqueue(
                backgroundTask(BackgroundTaskType.ADD_PUSHER, params, matrixConstraints = true)
        ).id
    }

    override suspend fun removePusher(pusher: Pusher) {
        removePusher(pusher.pushKey, pusher.appId)
    }

    override suspend fun removeHttpPusher(pushkey: String, appId: String) {
        removePusher(pushkey, appId)
    }

    override suspend fun removeEmailPusher(email: String) {
        removePusher(pushKey = email, Pusher.APP_ID_EMAIL)
    }

    private suspend fun removePusher(pushKey: String, pushAppId: String) {
        val params = RemovePusherTask.Params(pushKey, pushAppId)
        removePusherTask.execute(params)
    }

    override fun getPushersFlow(): Flow<List<Pusher>> {
        return database.pusherQueries.selectAll().asFlow().mapToList(dispatcher)
                .map { stores.pushers.getAll().map { entity -> entity.asDomain() } }
    }

    override fun getPushers(): List<Pusher> {
        return stores.pushers.getAll().map { it.asDomain() }
    }

    companion object {
        const val EVENT_ID_ONLY = "event_id_only"
    }
}
