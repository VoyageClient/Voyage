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

import org.matrix.android.sdk.api.util.Cancelable
import org.matrix.android.sdk.internal.platform.BackgroundQueuePolicy
import org.matrix.android.sdk.internal.platform.BackgroundTaskRequest
import org.matrix.android.sdk.internal.platform.BackgroundTaskScheduler
import javax.inject.Inject

/**
 * Helper class for sending event related works.
 * All send event from a room are using the same workchain, in order to ensure order.
 * Tasks must always report success (even on server error, marking the event as failed to send),
 * or the chain would be doomed in failed state.
 */
internal class TimelineSendEventWorkCommon @Inject constructor(
        private val backgroundTaskScheduler: BackgroundTaskScheduler,
) {

    fun postWork(roomId: String, request: BackgroundTaskRequest<*>, policy: BackgroundQueuePolicy = BackgroundQueuePolicy.APPEND_OR_REPLACE): Cancelable {
        return backgroundTaskScheduler.enqueueUnique(buildWorkName(roomId), policy, request)
    }

    private fun buildWorkName(roomId: String): String {
        return "${roomId}_$SEND_WORK"
    }

    companion object {
        private const val SEND_WORK = "SEND_WORK"
    }
}
