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

package org.matrix.android.sdk.internal.session.profile

import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.api.auth.UIABaseAuth
import org.matrix.android.sdk.api.auth.UserInteractiveAuthInterceptor
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.toRegistrationFlowResponse
import org.matrix.android.sdk.api.session.identity.ThreePid
import org.matrix.android.sdk.api.session.uia.UiaResult
import org.matrix.android.sdk.internal.auth.registration.handleUIA
import org.matrix.android.sdk.internal.database.model.PendingThreePidEntity
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.task.Task
import timber.log.Timber
import javax.inject.Inject

internal abstract class FinalizeAddingThreePidTask : Task<FinalizeAddingThreePidTask.Params, Unit> {
    data class Params(
            val threePid: ThreePid,
            val userInteractiveAuthInterceptor: UserInteractiveAuthInterceptor?,
            val userAuthParam: UIABaseAuth? = null,
            val userWantsToCancel: Boolean
    )
}

internal class DefaultFinalizeAddingThreePidTask @Inject constructor(
        private val profileAPI: ProfileAPI,
        @SessionDatabase private val database: org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase,
        @SessionDatabase private val dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        private val stores: org.matrix.android.sdk.internal.database.sql.store.SessionStores,
        private val pendingThreePidMapper: PendingThreePidMapper,
        private val globalErrorReceiver: GlobalErrorReceiver
) : FinalizeAddingThreePidTask() {

    override suspend fun execute(params: Params) {
        val canCleanup = if (params.userWantsToCancel) {
            true
        } else {
            // Get the required pending data
            val pendingThreePids = stores.threePid.getPendingThreePids()
                    .map { pendingThreePidMapper.map(it) }
                    .firstOrNull { it.threePid == params.threePid }
                    ?: throw IllegalArgumentException("unknown threepid")

            try {
                executeRequest(globalErrorReceiver) {
                    val body = FinalizeAddThreePidBody(
                            clientSecret = pendingThreePids.clientSecret,
                            sid = pendingThreePids.sid,
                            auth = params.userAuthParam?.asMap()
                    )
                    profileAPI.finalizeAddThreePid(body)
                }
                true
            } catch (throwable: Throwable) {
                if (params.userInteractiveAuthInterceptor == null ||
                        handleUIA(
                                failure = throwable,
                                interceptor = params.userInteractiveAuthInterceptor,
                                retryBlock = { authUpdate ->
                                    execute(params.copy(userAuthParam = authUpdate))
                                }
                        ) != UiaResult.SUCCESS
                ) {
                    Timber.d("## UIA: propagate failure")
                    throw throwable.toRegistrationFlowResponse()
                            ?.let { Failure.RegistrationFlowError(it) }
                            ?: throwable
                } else {
                    false
                }
            }
        }

        if (canCleanup) {
            cleanupDatabase()
        }
    }

    private suspend fun cleanupDatabase() {
        // Delete the pending three pid. Pending 3pids are transient; clearing all is acceptable here.
        database.awaitDbTransaction(dispatcher) {
            stores.threePid.clearPendingThreePids()
        }
    }
}
