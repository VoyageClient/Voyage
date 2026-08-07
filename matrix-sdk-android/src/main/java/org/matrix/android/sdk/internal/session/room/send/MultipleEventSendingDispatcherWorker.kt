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

package org.matrix.android.sdk.internal.session.room.send

import android.content.Context
import androidx.work.WorkerParameters
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.internal.SessionManager
import org.matrix.android.sdk.internal.platform.BackgroundTaskRequest
import org.matrix.android.sdk.internal.platform.BackgroundTaskType
import org.matrix.android.sdk.internal.platform.backgroundTask
import org.matrix.android.sdk.internal.session.SessionComponent
import org.matrix.android.sdk.internal.session.content.UploadContentWorker
import org.matrix.android.sdk.internal.session.room.timeline.TimelineSendEventWorkCommon
import org.matrix.android.sdk.internal.worker.SessionSafeCoroutineWorker
import timber.log.Timber
import javax.inject.Inject

/**
 * This worker creates a new work for each events passed in parameter.
 *
 * Possible previous worker: Always [UploadContentWorker].
 * Possible next worker    : None, but it will post new work to send events, encrypted or not.
 */
private typealias Params = MultipleEventSendingDispatcherWorkerParams

internal class MultipleEventSendingDispatcherWorker(context: Context, params: WorkerParameters, sessionManager: SessionManager) :
        SessionSafeCoroutineWorker<MultipleEventSendingDispatcherWorkerParams>(context, params, sessionManager, Params::class.java) {

    @Inject lateinit var timelineSendEventWorkCommon: TimelineSendEventWorkCommon
    @Inject lateinit var localEchoRepository: LocalEchoRepository

    override fun doOnError(params: Params, failureMessage: String): Result {
        params.localEchoIds.forEach { localEchoIds ->
            localEchoRepository.updateSendState(
                    eventId = localEchoIds.eventId,
                    roomId = localEchoIds.roomId,
                    sendState = SendState.UNDELIVERED,
                    sendStateDetails = params.lastFailureMessage
            )
        }

        return super.doOnError(params, failureMessage)
    }

    override fun injectWith(injector: SessionComponent) {
        injector.inject(this)
    }

    override suspend fun doSafeWork(params: Params): Result {
        Timber.v("## SendEvent: Start dispatch sending multiple event work")
        // Create a work for every event
        params.localEchoIds.forEach { localEchoIds ->
            val roomId = localEchoIds.roomId
            val eventId = localEchoIds.eventId
            localEchoRepository.updateSendState(eventId, roomId, SendState.SENDING)
            Timber.v("## SendEvent: Schedule send event $eventId")
            val sendWork = createSendEventWork(params.sessionId, eventId, true)
            timelineSendEventWorkCommon.postWork(roomId, sendWork)
        }

        return Result.success()
    }

    override fun buildErrorParams(params: Params, message: String): Params {
        return params.copy(lastFailureMessage = params.lastFailureMessage ?: message)
    }

    private fun createSendEventWork(sessionId: String, eventId: String, startChain: Boolean): BackgroundTaskRequest<SendEventWorker.Params> {
        return backgroundTask(
                type = BackgroundTaskType.SEND_EVENT,
                params = SendEventWorker.Params(sessionId = sessionId, eventId = eventId),
                matrixConstraints = true,
                isolateInput = startChain,
        )
    }
}
