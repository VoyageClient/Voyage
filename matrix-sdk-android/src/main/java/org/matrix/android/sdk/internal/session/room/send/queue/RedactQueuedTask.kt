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

import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.internal.crypto.tasks.RedactEventTask
import org.matrix.android.sdk.internal.session.room.send.CancelSendTracker
import org.matrix.android.sdk.internal.session.room.send.LocalEchoRepository
import timber.log.Timber

internal class RedactQueuedTask(
        private val toRedactEventId: String,
        val redactionLocalEchoId: String,
        private val roomId: String,
        private val reason: String?,
        private val withRelTypes: List<String>?,
        private val redactEventTask: RedactEventTask,
        private val localEchoRepository: LocalEchoRepository,
        private val cancelSendTracker: CancelSendTracker
) : QueuedTask(queueIdentifier = roomId, taskIdentifier = redactionLocalEchoId) {

    override suspend fun doExecute() {
        // The target may have been a still-sending local echo when the redaction was queued. The room
        // queue is sequential, so its send task has finished by now: redact the real event id. If it
        // never reached the server, fall back to cancelling the echo locally instead of sending a
        // redaction of a "$local." id.
        val targetEventId = if (LocalEcho.isLocalEchoId(toRedactEventId)) {
            val remoteId = localEchoRepository.getRemoteEchoId(toRedactEventId)
            if (remoteId == null) {
                Timber.w("Redaction target $toRedactEventId was never sent, cancelling it locally")
                cancelSendTracker.markLocalEchoForCancel(toRedactEventId, roomId)
                localEchoRepository.deleteFailedEchoAsync(roomId, toRedactEventId)
                localEchoRepository.deleteFailedEchoAsync(roomId, redactionLocalEchoId)
                return
            }
            remoteId
        } else {
            toRedactEventId
        }
        redactEventTask.execute(RedactEventTask.Params(redactionLocalEchoId, roomId, targetEventId, reason, withRelTypes))
    }

    override fun onTaskFailed() {
        // A redaction echo is a fake aggregation event with no retry UX; surfacing it as UNDELIVERED
        // leaves a stuck red room warning the user can't clear. Drop it, like reactions/redactions in
        // SendEventQueuedTask. The optimistic local prune of the target stays until next cache reload.
        localEchoRepository.deleteFailedEchoAsync(roomId, redactionLocalEchoId)
    }

    override fun isCancelled(): Boolean {
        return super.isCancelled() || cancelSendTracker.isCancelRequestedFor(redactionLocalEchoId, roomId)
    }
}
