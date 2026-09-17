/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list

internal class VisibleRoomTracker {
    private val counts = mutableMapOf<String, Int>()

    fun update(roomId: String, visible: Boolean): Boolean {
        val previousCount = counts[roomId] ?: 0
        if (visible) {
            counts[roomId] = previousCount + 1
        } else if (previousCount <= 1) {
            counts.remove(roomId)
        } else {
            counts[roomId] = previousCount - 1
        }
        return (previousCount > 0) != counts.containsKey(roomId)
    }

    fun roomIds(): Set<String> = counts.keys.toSet()
}
