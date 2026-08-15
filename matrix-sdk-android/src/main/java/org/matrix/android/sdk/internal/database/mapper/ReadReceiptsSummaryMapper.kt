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

package org.matrix.android.sdk.internal.database.mapper

import org.matrix.android.sdk.api.session.profile.ProfileOverrides
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.ReadReceipt
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.internal.database.model.ReadReceiptsSummaryEntity
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import timber.log.Timber
import javax.inject.Inject

internal class ReadReceiptsSummaryMapper @Inject constructor(
        private val stores: SessionStores,
) {

    fun map(readReceiptsSummaryEntity: ReadReceiptsSummaryEntity?): List<ReadReceipt> {
        readReceiptsSummaryEntity ?: return emptyList()
        return readReceiptsSummaryEntity.readReceipts.map { receipt ->
            // A receipt can name someone with no usable member row: the timeline's member load is still
            // in flight, or they have since left (leave events carry no profile). Fall back to the cached
            // global profile, then to the bare user id, rather than dropping the receipt.
            val member = stores.roomMember.getByRoomAndUser(receipt.roomId, receipt.userId)?.asDomain()
            val user = member?.takeIf { !it.displayName.isNullOrBlank() }
                    ?: globalProfileOf(receipt.userId, member)
                    ?: member
                    ?: RoomMemberSummary(membership = Membership.JOIN, userId = receipt.userId)
            if (user.displayName.isNullOrBlank() && shouldLog(receipt.roomId, receipt.userId)) {
                Timber.i(
                        "RRDBG map ${receipt.roomId} ${receipt.userId} unresolved " +
                                "memberRow=${member != null} membership=${member?.membership} " +
                                "globalUser=${stores.user.getUser(receipt.userId) != null}"
                )
            }
            val effectiveUser = user.copy(
                    displayName = ProfileOverrides.displayNameFor(receipt.userId) ?: user.displayName,
                    avatarUrl = ProfileOverrides.avatarUrlFor(receipt.userId) ?: user.avatarUrl,
            )
            ReadReceipt(effectiveUser, receipt.originServerTs.toLong(), receipt.threadId)
        }
    }

    // The mapper runs per event per rebuild, so an unresolved reader would otherwise log thousands of
    // times a minute. Once a minute per reader keeps a live trace in the log ring across a long watch.
    private val lastLoggedAt = HashMap<String, Long>()

    private fun shouldLog(roomId: String, userId: String): Boolean = synchronized(lastLoggedAt) {
        val now = System.currentTimeMillis()
        val key = "$roomId|$userId"
        if (now - (lastLoggedAt[key] ?: 0L) < LOG_THROTTLE_MS) return false
        lastLoggedAt[key] = now
        true
    }

    private fun globalProfileOf(userId: String, member: RoomMemberSummary?): RoomMemberSummary? {
        val profile = stores.user.getUser(userId)?.takeIf { it.displayName.isNotBlank() } ?: return null
        return RoomMemberSummary(
                membership = member?.membership ?: Membership.JOIN,
                userId = userId,
                displayName = profile.displayName,
                avatarUrl = profile.avatarUrl,
        )
    }

    private companion object {
        private const val LOG_THROTTLE_MS = 60_000L
    }
}
