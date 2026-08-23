/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.membership

import org.matrix.android.sdk.api.session.profile.ColorPreference
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.profile.ExtendedProfileCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Per-room MSC4522 colors keyed by room+user, so the timeline mapper doesn't hit the member table
 * for every event. Timeline rows snapshot the sender's name/avatar at send time, but the color
 * follows the current member state like other clients do.
 */
@SessionScope
internal class RoomMemberColorCache @Inject constructor(
        private val stores: SessionStores,
        private val extendedProfileCache: ExtendedProfileCache,
) {
    private val cache = ConcurrentHashMap<String, Optional<ColorPreference>>()

    private val generationCounter = AtomicLong(0)
    val generation: Long get() = generationCounter.get()

    fun get(roomId: String, userId: String): ColorPreference? {
        val key = key(roomId, userId)
        cache[key]?.let { return it.getOrNull() }
        val color = stores.roomMember.getByRoomAndUser(roomId, userId)?.colorPreference
        cache[key] = Optional.from(color)
        return color
    }

    fun put(roomId: String, userId: String, color: ColorPreference?) {
        val key = key(roomId, userId)
        val updated = Optional.from(color)
        val previous = cache[key]
        // Skip redundant colorless writes (the common case on initial sync) so they don't churn the
        // generation and invalidate every open timeline for nothing.
        if (previous == null && color == null) return
        if (previous == updated) return
        cache[key] = updated
        generationCounter.incrementAndGet()
        extendedProfileCache.notifyColorChanged(userId)
    }

    private fun key(roomId: String, userId: String) = "$roomId|$userId"
}
