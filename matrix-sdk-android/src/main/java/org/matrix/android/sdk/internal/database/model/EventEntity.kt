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

package org.matrix.android.sdk.internal.database.model

import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.threads.ThreadNotificationState

internal open class EventEntity(
        var eventId: String = "",
        var roomId: String = "",
        var type: String = "",
        var content: String? = null,
        var prevContent: String? = null,
        var isUseless: Boolean = false,
        var stateKey: String? = null,
        var originServerTs: Long? = null,
        var sender: String? = null,
        // Can contain a serialized MatrixError
        var sendStateDetails: String? = null,
        var age: Long? = 0,
        var unsignedData: String? = null,
        var redacts: String? = null,
        var decryptionResultJson: String? = null,
        var ageLocalTs: Long? = null,
        // Thread related, no need to create a new Entity for performance
        var isRootThread: Boolean = false,
        var rootThreadEventId: String? = null,
        // Number messages within the thread
        var numberOfThreads: Int = 0,
        var threadSummaryLatestMessage: TimelineEventEntity? = null,
        var isVerificationStateDirty: Boolean? = null,
) {

    private var sendStateStr: String = SendState.UNKNOWN.name

    var sendState: SendState
        get() {
            return SendState.valueOf(sendStateStr)
        }
        set(value) {
            sendStateStr = value.name
        }

    private var threadNotificationStateStr: String = ThreadNotificationState.NO_NEW_MESSAGE.name
    var threadNotificationState: ThreadNotificationState
        get() {
            return ThreadNotificationState.valueOf(threadNotificationStateStr)
        }
        set(value) {
            threadNotificationStateStr = value.name
        }

    var decryptionErrorCode: String? = null
        set(value) {
            if (value != field) field = value
        }

    var decryptionErrorReason: String? = null
        set(value) {
            if (value != field) field = value
        }

    companion object

    fun isThread(): Boolean = rootThreadEventId != null
}
