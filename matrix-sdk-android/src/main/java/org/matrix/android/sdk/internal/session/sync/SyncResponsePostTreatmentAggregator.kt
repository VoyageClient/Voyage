/*
 * Copyright (c) 2021 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.sync

import org.matrix.android.sdk.internal.crypto.store.db.CryptoStoreAggregator

internal class SyncResponsePostTreatmentAggregator {
    // List of RoomId
    val ephemeralFilesToDelete = mutableListOf<String>()

    // Map of roomId to directUserId
    val directChatsToCheck = mutableMapOf<String, String>()

    // Set of userIds to fetch and update at the end of incremental syncs
    val userIdsToFetch = mutableSetOf<String>()

    // Set of users to call `crossSigningService.checkTrustAndAffectedRoomShields` once per sync

    val roomsWithMembershipChangesForShieldUpdate = mutableSetOf<String>()

    // Users removed from the ignore list this sync; triggers a non-destructive catch-up sync that
    // rediscovers invites which were hidden by the server while they were ignored.
    val unIgnoredUserIds = mutableSetOf<String>()

    // Set to true when a sync carries changes that can affect the space parent/child graph (membership
    // transitions, space child/parent, create, power levels, name, DM status). Plain message-only syncs
    // leave it false so we can skip the expensive full hierarchy revalidation.
    var spaceHierarchyChanged = false

    // Rooms rejoined over a retained kicked/banned timeline; their join window needs re-anchoring
    // (see ReanchorRejoinedRoomTask).
    val rejoinedRoomsToReanchor = mutableSetOf<String>()

    // For the crypto store
    val cryptoStoreAggregator = CryptoStoreAggregator()
}
