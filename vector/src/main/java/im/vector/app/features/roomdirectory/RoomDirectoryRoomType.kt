/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomdirectory

import org.matrix.android.sdk.api.session.room.model.RoomType

/**
 * Room-type filter for the public room directory (MSC3827).
 */
enum class RoomDirectoryRoomType {
    ALL,
    ROOMS,
    SPACES;

    /**
     * The `room_types` filter value, or null to send no filter at all. A null *entry* in the list
     * means "rooms without a type", which is how the spec addresses ordinary rooms.
     */
    fun toFilterValue(): List<String?>? = when (this) {
        ALL -> null
        ROOMS -> listOf(null)
        SPACES -> listOf(RoomType.SPACE)
    }
}
