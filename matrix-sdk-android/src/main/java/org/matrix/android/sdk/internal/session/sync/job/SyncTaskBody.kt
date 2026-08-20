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
package org.matrix.android.sdk.internal.session.sync.job

import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.failure.isTokenError
import org.matrix.android.sdk.internal.platform.BackgroundTaskScheduler
import org.matrix.android.sdk.internal.session.sync.SyncPresence
import org.matrix.android.sdk.internal.session.sync.SyncTask
import org.matrix.android.sdk.internal.worker.BackgroundTaskBody
import org.matrix.android.sdk.internal.worker.BackgroundTaskContext
import org.matrix.android.sdk.internal.worker.BackgroundTaskOutcome
import timber.log.Timber
import javax.inject.Inject

internal class SyncTaskBody @Inject constructor(
        private val syncTask: SyncTask,
        private val backgroundTaskScheduler: BackgroundTaskScheduler,
) : BackgroundTaskBody<SyncWorkerParams> {

    override suspend fun execute(params: SyncWorkerParams, context: BackgroundTaskContext): BackgroundTaskOutcome {
        Timber.i("Sync work starting")

        return runCatching {
            doSync(if (params.forceImmediate) 0 else params.timeout)
        }.fold(
                { hasToDeviceEvents ->
                    BackgroundTaskOutcome.Success.also {
                        if (params.periodic) {
                            // we want to schedule another one after a delay, or immediately if hasToDeviceEvents
                            BackgroundSyncScheduling.automaticallyBackgroundSync(
                                    backgroundTaskScheduler = backgroundTaskScheduler,
                                    sessionId = params.sessionId,
                                    serverTimeoutInSeconds = params.timeout,
                                    delayInSeconds = params.delay,
                                    forceImmediate = hasToDeviceEvents
                            )
                        } else if (hasToDeviceEvents) {
                            // Previous response has toDevice events, request an immediate sync request
                            BackgroundSyncScheduling.requireBackgroundSync(
                                    backgroundTaskScheduler = backgroundTaskScheduler,
                                    sessionId = params.sessionId,
                                    serverTimeoutInSeconds = 0
                            )
                        }
                    }
                },
                { failure ->
                    if (failure.isTokenError()) {
                        BackgroundTaskOutcome.Failure
                    } else {
                        // If the task was stopped (when going back in foreground), a JobCancellation exception is sent
                        // but in this case the result is ignored, as the work is considered stopped,
                        // so don't worry of the retry here for this case
                        BackgroundTaskOutcome.Retry
                    }
                }
        )
    }

    /**
     * Will return true if the sync response contains some toDevice events.
     */
    private suspend fun doSync(timeout: Long): Boolean {
        val taskParams = SyncTask.Params(timeout * 1000, SyncPresence.Offline, afterPause = false)
        val syncResponse = syncTask.execute(taskParams)
        return syncResponse.toDevice?.events?.isNotEmpty().orFalse()
    }
}
