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

package org.matrix.android.sdk.internal.session.room.send

import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.internal.crypto.tasks.RedactEventTask
import org.matrix.android.sdk.internal.worker.BackgroundTaskBody
import org.matrix.android.sdk.internal.worker.BackgroundTaskContext
import org.matrix.android.sdk.internal.worker.BackgroundTaskOutcome
import javax.inject.Inject

internal class RedactEventTaskBody @Inject constructor(
        private val redactEventTask: RedactEventTask,
) : BackgroundTaskBody<RedactEventWorkerParams> {

    override suspend fun execute(params: RedactEventWorkerParams, context: BackgroundTaskContext): BackgroundTaskOutcome {
        return runCatching {
            redactEventTask.execute(
                    RedactEventTask.Params(
                            txID = params.txID,
                            roomId = params.roomId,
                            eventId = params.eventId,
                            reason = params.reason,
                            withRelTypes = params.withRelTypes,
                    )
            )
        }.fold(
                {
                    BackgroundTaskOutcome.Success
                },
                {
                    when (it) {
                        is Failure.NetworkConnection -> BackgroundTaskOutcome.Retry
                        else -> {
                            // TODO mark as failed to send?
                            // always return success, or the chain will be stuck for ever!
                            BackgroundTaskOutcome.SuccessWith(params.copy(lastFailureMessage = it.localizedMessage))
                        }
                    }
                }
        )
    }
}
