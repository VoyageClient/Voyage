/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.internal.platform.BackgroundTaskRequest
import org.matrix.android.sdk.internal.platform.BackgroundTaskType
import org.matrix.android.sdk.internal.platform.backgroundTask
import org.matrix.android.sdk.internal.session.room.timeline.TimelineSendEventWorkCommon
import org.matrix.android.sdk.internal.worker.BackgroundTaskBody
import org.matrix.android.sdk.internal.worker.BackgroundTaskContext
import org.matrix.android.sdk.internal.worker.BackgroundTaskOutcome
import timber.log.Timber
import javax.inject.Inject

/**
 * Creates a new send task for each event passed in parameter.
 *
 * Possible previous task: Always the content upload.
 * Possible next task    : None, but it will post new work to send events, encrypted or not.
 */
internal class MultipleEventSendingDispatcherTaskBody @Inject constructor(
        private val timelineSendEventWorkCommon: TimelineSendEventWorkCommon,
        private val localEchoRepository: LocalEchoRepository,
) : BackgroundTaskBody<MultipleEventSendingDispatcherWorkerParams> {

    override suspend fun execute(
            params: MultipleEventSendingDispatcherWorkerParams,
            context: BackgroundTaskContext
    ): BackgroundTaskOutcome {
        Timber.v("## SendEvent: Start dispatch sending multiple event work")
        // Create a work for every event
        params.localEchoIds.forEach { localEchoIds ->
            val roomId = localEchoIds.roomId
            val eventId = localEchoIds.eventId
            if (localEchoRepository.getUpToDateEcho(eventId)?.sendState == SendState.UNDELIVERED) {
                // A gallery item upload failed after this chain was enqueued — don't send a half-filled event.
                Timber.v("## SendEvent: Skip undelivered echo $eventId")
                return@forEach
            }
            localEchoRepository.updateSendState(eventId, roomId, SendState.SENDING)
            Timber.v("## SendEvent: Schedule send event $eventId")
            val sendWork = createSendEventWork(params.sessionId, eventId, true)
            timelineSendEventWorkCommon.postWork(roomId, sendWork)
        }

        return BackgroundTaskOutcome.Success
    }

    override fun onError(params: MultipleEventSendingDispatcherWorkerParams, failureMessage: String): BackgroundTaskOutcome {
        params.localEchoIds.forEach { localEchoIds ->
            localEchoRepository.updateSendState(
                    eventId = localEchoIds.eventId,
                    roomId = localEchoIds.roomId,
                    sendState = SendState.UNDELIVERED,
                    sendStateDetails = params.lastFailureMessage
            )
        }

        Timber.e("Work cancelled due to input error from parent: $failureMessage")
        return BackgroundTaskOutcome.SuccessWith(params)
    }

    private fun createSendEventWork(sessionId: String, eventId: String, startChain: Boolean): BackgroundTaskRequest<SendEventWorkerParams> {
        return backgroundTask(
                type = BackgroundTaskType.SEND_EVENT,
                params = SendEventWorkerParams(sessionId = sessionId, eventId = eventId),
                matrixConstraints = true,
                isolateInput = startChain,
        )
    }
}
