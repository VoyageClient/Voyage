/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.membership

import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.debug.OperationDiagnostics
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.session.SessionScope
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

@SessionScope
internal class AmbiguousDisplayNameCache @Inject constructor(
        private val stores: SessionStores,
) {
    private val cache = ConcurrentHashMap<String, RoomMemberNameIndex>()

    // Cache missing member state too; ConcurrentHashMap.newKeySet() requires API 24.
    private val membersUnknown = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val generationCounter = AtomicLong(0)
    val generation: Long get() = generationCounter.get()

    /** Null means member state is unavailable; retain the stored uniqueness flag. */
    fun isAmbiguous(roomId: String, displayName: String?, userId: String? = null): Boolean? {
        if (displayName.isNullOrEmpty()) return false
        val known = cache[roomId] ?: load(roomId) ?: return null
        return known.isAmbiguous(displayName, userId)
    }

    private fun load(roomId: String): RoomMemberNameIndex? {
        if (roomId in membersUnknown) return null
        val members = stores.roomMember.getByRoom(roomId)
        if (members.isEmpty()) {
            membersUnknown.add(roomId)
            return null
        }
        val index = RoomMemberNameIndex.fromMembers(members)
        cache[roomId] = index
        DebugLog.i { "MEMBERDBG stage=name-index-loaded room=${OperationDiagnostics.id(roomId)} members=${members.size} active=${members.count { it.membership.isActive() }}" }
        return index
    }

    fun invalidate(roomId: String) {
        val hadUnknown = membersUnknown.remove(roomId)
        if (cache.remove(roomId) != null || hadUnknown) {
            generationCounter.incrementAndGet()
            DebugLog.i { "MEMBERDBG stage=name-index-invalidated room=${OperationDiagnostics.id(roomId)} generation=$generation" }
        }
    }
}
