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

import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.ReadReceipt
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.internal.database.model.ReadReceiptsSummaryEntity
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import javax.inject.Inject

internal class ReadReceiptsSummaryMapper @Inject constructor(
        private val stores: SessionStores,
) {

    fun map(readReceiptsSummaryEntity: ReadReceiptsSummaryEntity?): List<ReadReceipt> {
        readReceiptsSummaryEntity ?: return emptyList()
        return readReceiptsSummaryEntity.readReceipts.map { receipt ->
            // A receipt can name someone with no member row yet: the timeline's member load is still in
            // flight, or they have since left. Show the bare user id rather than dropping the receipt.
            val user = stores.roomMember.getByRoomAndUser(receipt.roomId, receipt.userId)?.asDomain()
                    ?: RoomMemberSummary(membership = Membership.JOIN, userId = receipt.userId)
            ReadReceipt(user, receipt.originServerTs.toLong(), receipt.threadId)
        }
    }
}
