/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import org.matrix.android.sdk.api.failure.shouldBeRetried
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.internal.crypto.tasks.SendEventTask
import org.matrix.android.sdk.internal.session.media.LinkPreviewPrefetcher
import org.matrix.android.sdk.internal.util.toMatrixErrorStr
import org.matrix.android.sdk.internal.worker.BackgroundTaskBody
import org.matrix.android.sdk.internal.worker.BackgroundTaskContext
import org.matrix.android.sdk.internal.worker.BackgroundTaskOutcome
import timber.log.Timber
import javax.inject.Inject

internal class SendEventTaskBody @Inject constructor(
        private val localEchoRepository: LocalEchoRepository,
        private val sendEventTask: SendEventTask,
        private val cryptoService: CryptoService,
        private val cancelSendTracker: CancelSendTracker,
        private val urlPreviewBundler: LinkPreviewPrefetcher,
) : BackgroundTaskBody<SendEventWorkerParams> {

    override suspend fun execute(params: SendEventWorkerParams, context: BackgroundTaskContext): BackgroundTaskOutcome {
        val event = localEchoRepository.getUpToDateEcho(params.eventId)
        val eventId = event?.eventId
        val roomId = event?.roomId
        if (event == null || eventId == null || roomId == null) {
            localEchoRepository.updateSendState(params.eventId, roomId, SendState.UNDELIVERED)
            return BackgroundTaskOutcome.Success
                    .also { Timber.e("Work cancelled due to bad input data") }
        }

        if (cancelSendTracker.isCancelRequestedFor(params.eventId, roomId)) {
            return BackgroundTaskOutcome.Success
                    .also {
                        cancelSendTracker.markCancelled(eventId, roomId)
                        Timber.e("## SendEvent: Event sending has been cancelled ${params.eventId}")
                    }
        }

        Timber.v("## SendEvent: Send event ${params.eventId}")
        return try {
            val encrypt = params.isEncrypted ?: cryptoService.isRoomEncrypted(roomId)
            // Uploaded media skips the send queue, so its caption's links get bundled (MSC4095) here instead.
            val eventToSend = urlPreviewBundler.bundleUrlPreviews(event, encrypt)
            sendEventTask.execute(SendEventTask.Params(eventToSend, encrypt))
            BackgroundTaskOutcome.Success
        } catch (exception: Throwable) {
            if (!exception.shouldBeRetried()) {
                Timber.e("## SendEvent: Send event Failed cannot retry ${params.eventId} > ${exception.localizedMessage}")
                localEchoRepository.updateSendState(
                        eventId = eventId,
                        roomId = roomId,
                        sendState = SendState.UNDELIVERED,
                        sendStateDetails = exception.toMatrixErrorStr()
                )
                BackgroundTaskOutcome.Success
            } else {
                Timber.e("## SendEvent: Send event Failed schedule retry ${params.eventId} > ${exception.localizedMessage}")
                BackgroundTaskOutcome.Retry
            }
        }
    }
}
