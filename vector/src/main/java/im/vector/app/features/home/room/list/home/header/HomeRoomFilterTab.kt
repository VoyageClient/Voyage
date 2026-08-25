/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list.home.header

/**
 * A tab in the new-layout filter row: either one of the built-in filters or a custom room-list
 * section (Element Web "Sections"), which the single-list layout surfaces as a filter chip.
 */
sealed class HomeRoomFilterTab {
    data class Standard(val filter: HomeRoomFilter) : HomeRoomFilterTab()
    data class Section(val tag: String, val name: String) : HomeRoomFilterTab()

    /** Same tab identity, ignoring display-only fields (a renamed section stays selected). */
    fun isSameTab(other: HomeRoomFilterTab): Boolean = when {
        this is Standard && other is Standard -> filter == other.filter
        this is Section && other is Section -> tag == other.tag
        else -> false
    }
}
