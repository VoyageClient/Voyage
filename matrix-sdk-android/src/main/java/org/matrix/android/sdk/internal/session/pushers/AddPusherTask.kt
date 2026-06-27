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

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.session.pushers.PusherState
import org.matrix.android.sdk.internal.database.mapper.toEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.RequestExecutor
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface AddPusherTask : Task<AddPusherTask.Params, Unit> {
    data class Params(val pusher: JsonPusher)
}

internal class DefaultAddPusherTask @Inject constructor(
        private val pushersAPI: PushersAPI,
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val requestExecutor: RequestExecutor,
        private val globalErrorReceiver: GlobalErrorReceiver,
) : AddPusherTask {

    override suspend fun execute(params: AddPusherTask.Params) {
        val pusher = params.pusher
        try {
            setPusher(pusher)
        } catch (error: Throwable) {
            database.awaitDbTransaction(dispatcher) {
                if (stores.pushers.getByPushKey(pusher.pushKey).isNotEmpty()) {
                    stores.pushers.updateState(pusher.pushKey, PusherState.FAILED_TO_REGISTER)
                }
            }
            throw error
        }
    }

    private suspend fun setPusher(pusher: JsonPusher) {
        requestExecutor.executeRequest(globalErrorReceiver) {
            pushersAPI.setPusher(pusher)
        }
        database.awaitDbTransaction(dispatcher) {
            stores.pushers.replaceByPushKey(pusher.toEntity().also { it.state = PusherState.REGISTERED })
        }
    }
}
