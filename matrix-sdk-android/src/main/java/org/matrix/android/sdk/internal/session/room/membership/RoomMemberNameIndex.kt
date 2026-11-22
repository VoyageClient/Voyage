/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.membership

import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.internal.database.model.RoomMemberSummaryEntity

internal class RoomMemberNameIndex private constructor() {
    // Null marks a name shared by multiple active accounts.
    private val owners = HashMap<String, String?>()

    fun isAmbiguous(displayName: String?, userId: String? = null): Boolean {
        if (displayName.isNullOrEmpty() || !owners.containsKey(displayName)) return false
        val owner = owners[displayName]
        return owner == null || (userId != null && owner != userId)
    }

    private fun add(userId: String, displayName: String?, membership: Membership) {
        if (!membership.isActive() || displayName.isNullOrEmpty()) return
        if (!owners.containsKey(displayName)) {
            owners[displayName] = userId
        } else if (owners[displayName] != userId) {
            owners[displayName] = null
        }
    }

    companion object {
        fun fromMembers(members: List<RoomMemberSummaryEntity>) = RoomMemberNameIndex().apply {
            members.forEach { add(it.userId, it.displayName, it.membership) }
        }

        fun fromState(members: Map<String, RoomMemberContent?>) = RoomMemberNameIndex().apply {
            members.forEach { (userId, member) -> member?.let { add(userId, it.displayName, it.membership) } }
        }
    }
}
