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

package org.matrix.android.sdk.internal.session.room.send.queue

import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.util.MatrixJsonParser
import org.matrix.android.sdk.internal.crypto.tasks.SendEventTask
import org.matrix.android.sdk.internal.session.room.send.CancelSendTracker
import org.matrix.android.sdk.internal.session.room.send.LocalEchoRepository

internal class SendEventQueuedTask(
        val event: Event,
        val encrypt: Boolean,
        val sendEventTask: SendEventTask,
        val cryptoService: CryptoService,
        val localEchoRepository: LocalEchoRepository,
        val cancelSendTracker: CancelSendTracker
) : QueuedTask(queueIdentifier = event.roomId!!, taskIdentifier = event.eventId!!) {

    private var lastFailure: Throwable? = null

    override suspend fun doExecute() {
        try {
            sendEventTask.execute(SendEventTask.Params(remapLocalRelationTargets(event), encrypt))
        } catch (e: Throwable) {
            lastFailure = e
            throw e
        }
    }

    // A reaction/reply/thread event created against a still-sending message carries the target's
    // "$local." echo id. The room queue is sequential, so by now the target's send has finished:
    // rewrite the relation to the real event id (no mapping = target failed; send as-is, harmless).
    private fun remapLocalRelationTargets(event: Event): Event {
        val content = event.content ?: return event
        val relates = content["m.relates_to"] as? Map<*, *> ?: return event
        val newRelates = relates.toMutableMap()
        var changed = false
        (relates["event_id"] as? String)
                ?.let { targetId -> remoteIdFor(targetId) }
                ?.let { remoteId ->
                    newRelates["event_id"] = remoteId
                    changed = true
                }
        (relates["m.in_reply_to"] as? Map<*, *>)?.let { inReplyTo ->
            (inReplyTo["event_id"] as? String)
                    ?.let { targetId -> remoteIdFor(targetId) }
                    ?.let { remoteId ->
                        newRelates["m.in_reply_to"] = inReplyTo.toMutableMap().apply { put("event_id", remoteId) }
                        changed = true
                    }
        }
        if (!changed) return event
        return event.copy(content = content.toMutableMap().apply { put("m.relates_to", newRelates) })
    }

    private fun remoteIdFor(targetId: String): String? =
            targetId.takeIf { LocalEcho.isLocalEchoId(it) }?.let { localEchoRepository.getRemoteEchoId(it) }

    override fun onTaskFailed() {
        when (event.getClearType()) {
            EventType.REDACTION,
            EventType.REACTION -> {
                // we just delete? it will not be present on timeline and no ux to retry
                localEchoRepository.deleteFailedEchoAsync(eventId = event.eventId, roomId = event.roomId ?: "")
                // TODO update aggregation :/ or it will stay locally
            }
            else -> {
                localEchoRepository.updateSendState(
                        event.eventId!!,
                        event.roomId,
                        SendState.UNDELIVERED,
                        sendStateDetailsFor(lastFailure)
                )
            }
        }
    }

    override fun isCancelled(): Boolean {
        return super.isCancelled() || cancelSendTracker.isCancelRequestedFor(event.eventId, event.roomId)
    }

    private fun sendStateDetailsFor(failure: Throwable?): String? {
        val serverError = failure as? Failure.ServerError ?: return failure?.message
        return runCatching {
            MatrixJsonParser.getMoshi()
                    .adapter(MatrixError::class.java)
                    .toJson(serverError.error)
        }.getOrNull() ?: serverError.error.toString()
    }
}
