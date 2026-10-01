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

package org.matrix.android.sdk.internal.session.typing

import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.typing.TypingUsersTracker
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.room.summary.RoomSummaryPreviewInvalidation
import javax.inject.Inject

@SessionScope
internal class DefaultTypingUsersTracker @Inject constructor(
        private val previewInvalidation: RoomSummaryPreviewInvalidation,
) : TypingUsersTracker {

    private val typingUsers = mutableMapOf<String, List<SenderInfo>>()

    /**
     * Set all currently typing users for a room (excluding yourself). Returns whether the list changed.
     */
    fun setTypingUsersFromRoom(roomId: String, senderInfoList: List<SenderInfo>): Boolean {
        val hasNewValue = typingUsers[roomId] != senderInfoList
        if (hasNewValue) {
            typingUsers[roomId] = senderInfoList
            // Mapped summaries are memoized by their row, which typing doesn't change.
            previewInvalidation.onPreviewChanged(roomId)
        }
        return hasNewValue
    }

    override fun getTypingUsers(roomId: String): List<SenderInfo> {
        return typingUsers[roomId].orEmpty()
    }
}
