/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.pushers

import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.internal.worker.BackgroundTaskBody
import org.matrix.android.sdk.internal.worker.BackgroundTaskContext
import org.matrix.android.sdk.internal.worker.BackgroundTaskOutcome
import javax.inject.Inject

internal class AddPusherTaskBody @Inject constructor(
        private val addPusherTask: AddPusherTask
) : BackgroundTaskBody<AddPusherWorkerParams> {

    override suspend fun execute(params: AddPusherWorkerParams, context: BackgroundTaskContext): BackgroundTaskOutcome {
        val pusher = params.pusher

        if (pusher.pushKey.isBlank()) {
            return BackgroundTaskOutcome.Failure
        }
        return try {
            addPusherTask.execute(AddPusherTask.Params(pusher))
            BackgroundTaskOutcome.Success
        } catch (exception: Throwable) {
            when (exception) {
                is Failure.NetworkConnection -> BackgroundTaskOutcome.Retry
                else -> BackgroundTaskOutcome.Failure
            }
        }
    }
}
