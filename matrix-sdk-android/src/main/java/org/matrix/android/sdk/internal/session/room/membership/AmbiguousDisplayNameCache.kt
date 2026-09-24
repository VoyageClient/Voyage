/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.membership

import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.session.SessionScope
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Display names worn by more than one member of a room, so the timeline can say which is which.
 *
 * A timeline row records whether the name was unique when the row was written, which is wrong twice
 * over: members load lazily, so a name looked unique only because the other holder was not known yet;
 * and two rows from one sender written at different times disagree, which breaks the grouping of
 * consecutive messages. Read from the member table instead, as the color does.
 */
@SessionScope
internal class AmbiguousDisplayNameCache @Inject constructor(
        private val stores: SessionStores,
) {
    private val cache = ConcurrentHashMap<String, Set<String>>()

    // Rooms whose member rows aren't loaded yet. Without this, "not loaded" is not a cacheable answer, so
    // every timeline event re-ran the whole member query — ~11ms each, seconds across a chunk, on exactly
    // the cold open where members have yet to arrive.
    // newSetFromMap, not ConcurrentHashMap.newKeySet(): that static is API 24+ and this fork runs on 14.
    private val membersUnknown = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val generationCounter = AtomicLong(0)
    val generation: Long get() = generationCounter.get()

    /**
     * @return null when the room's members are not loaded, where the caller should keep whatever the
     * row recorded rather than claim every name is unique.
     */
    fun isAmbiguous(roomId: String, displayName: String?): Boolean? {
        if (displayName.isNullOrEmpty()) return false
        val known = cache[roomId] ?: load(roomId) ?: return null
        return displayName in known
    }

    private fun load(roomId: String): Set<String>? {
        if (roomId in membersUnknown) return null
        val members = stores.roomMember.getByRoom(roomId)
        if (members.isEmpty()) {
            membersUnknown.add(roomId)
            return null
        }
        val seen = HashSet<String>(members.size)
        val duplicated = HashSet<String>()
        members.forEach { member ->
            val name = member.displayName ?: return@forEach
            if (!seen.add(name)) duplicated.add(name)
        }
        cache[roomId] = duplicated
        return duplicated
    }

    /** A membership change can make a name ambiguous, or stop it being so. */
    fun invalidate(roomId: String) {
        val hadUnknown = membersUnknown.remove(roomId)
        if (cache.remove(roomId) != null || hadUnknown) generationCounter.incrementAndGet()
    }
}
