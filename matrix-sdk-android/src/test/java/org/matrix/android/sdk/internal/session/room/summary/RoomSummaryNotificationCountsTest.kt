/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.summary

import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.android.sdk.api.query.RoomCategoryFilter
import org.matrix.android.sdk.api.query.RoomTagQueryFilter
import org.matrix.android.sdk.api.query.SpaceFilter
import org.matrix.android.sdk.api.session.room.RoomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomType
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.spaceSummaryQueryParams
import org.matrix.android.sdk.internal.database.sql.Room_summary as RoomSummaryRow

/**
 * The room-list filter decides which rooms a section shows and which counts land on its badge, and it is
 * applied through [CompiledRoomFilter], which resolves the query up front and must stay equivalent to the
 * query it was built from. Each case pins the exact rooms a query shape selects.
 */
class RoomSummaryNotificationCountsTest {

    private val rooms = listOf(
            room("!dm:a", isDirect = true, notification = 3, highlight = 1, membership = "JOIN"),
            room("!dm-muted:a", isDirect = true, notification = 0, membership = "JOIN"),
            room("!room:a", notification = 5, highlight = 2, membership = "JOIN", parents = "|!space1|"),
            room("!room-in-two:a", notification = 7, membership = "JOIN", parents = "|!space1|!space2|"),
            room("!orphan:a", notification = 11, membership = "JOIN"),
            room("!invited:a", notification = 13, membership = "INVITE"),
            room("!left:a", notification = 17, membership = "LEAVE"),
            room("!space:a", roomType = RoomType.SPACE, notification = 19, membership = "JOIN"),
            room("!hidden:a", notification = 23, membership = "JOIN", hidden = true),
            room("!favourite:a", notification = 29, membership = "JOIN", favourite = true),
            room("!low:a", notification = 31, membership = "JOIN", lowPriority = true),
            room("!removed:a", notification = 37, membership = "JOIN", removed = true),
            room("!watched:a", notification = 41, membership = "JOIN", watched = true),
            room("!local.echo", notification = 43, membership = "JOIN"),
    )

    // room_id to its tags.
    private val tags = mapOf(
            "!room:a" to listOf("u.work"),
            "!room-in-two:a" to listOf("u.work", "u.personal"),
            "!orphan:a" to listOf("u.personal"),
            "!favourite:a" to listOf("m.favourite"),
    )

    private val queries = mapOf(
            "default" to roomSummaryQueryParams {},
            "joined DMs" to roomSummaryQueryParams {
                memberships = listOf(Membership.JOIN)
                roomCategoryFilter = RoomCategoryFilter.ONLY_DM
            },
            "joined rooms" to roomSummaryQueryParams {
                memberships = listOf(Membership.JOIN)
                roomCategoryFilter = RoomCategoryFilter.ONLY_ROOMS
            },
            "joined rooms in space1" to roomSummaryQueryParams {
                memberships = listOf(Membership.JOIN)
                roomCategoryFilter = RoomCategoryFilter.ONLY_ROOMS
                spaceFilter = SpaceFilter.ActiveSpace("!space1")
            },
            "joined rooms outside space1" to roomSummaryQueryParams {
                memberships = listOf(Membership.JOIN)
                spaceFilter = SpaceFilter.ExcludeSpace("!space1")
            },
            "orphans" to roomSummaryQueryParams {
                memberships = listOf(Membership.JOIN)
                spaceFilter = SpaceFilter.OrphanRooms
            },
            "invites" to roomSummaryQueryParams { memberships = listOf(Membership.INVITE) },
            "every membership" to roomSummaryQueryParams { memberships = emptyList() },
            "with notifications" to roomSummaryQueryParams {
                roomCategoryFilter = RoomCategoryFilter.ONLY_WITH_NOTIFICATIONS
            },
            "favourites" to roomSummaryQueryParams {
                roomTagQueryFilter = RoomTagQueryFilter(isFavorite = true, isLowPriority = null, isServerNotice = null)
            },
            "not low priority" to roomSummaryQueryParams {
                roomTagQueryFilter = RoomTagQueryFilter(isFavorite = null, isLowPriority = false, isServerNotice = null)
            },
            "removed from room" to roomSummaryQueryParams { removedFromRoom = true },
            "not removed from room" to roomSummaryQueryParams { removedFromRoom = false },
            "watched" to roomSummaryQueryParams { watched = true },
            "spaces" to spaceSummaryQueryParams { memberships = listOf(Membership.JOIN) },
            "tagged u.work" to roomSummaryQueryParams {
                memberships = listOf(Membership.JOIN)
                hasTag = "u.work"
            },
            "active tag filter u.personal" to roomSummaryQueryParams {
                memberships = listOf(Membership.JOIN)
                activeTagFilter = "u.personal"
            },
            "untagged catch-all" to roomSummaryQueryParams {
                memberships = listOf(Membership.JOIN)
                excludeTags = listOf("u.work", "u.personal")
            },
            "tagged and not excluded" to roomSummaryQueryParams {
                memberships = listOf(Membership.JOIN)
                hasTag = "u.work"
                excludeTags = listOf("u.personal")
            },
    )

    // The rooms each query shape must select, spelled out rather than derived.
    private val expectations = mapOf(
            "default" to setOf("!dm:a", "!dm-muted:a", "!room:a", "!room-in-two:a", "!orphan:a", "!invited:a",
                    "!left:a", "!favourite:a", "!low:a", "!removed:a", "!watched:a"),
            "joined DMs" to setOf("!dm:a", "!dm-muted:a"),
            "joined rooms" to setOf("!room:a", "!room-in-two:a", "!orphan:a", "!favourite:a", "!low:a", "!removed:a", "!watched:a"),
            "joined rooms in space1" to setOf("!room:a", "!room-in-two:a"),
            "joined rooms outside space1" to setOf("!dm:a", "!dm-muted:a", "!orphan:a", "!favourite:a", "!low:a",
                    "!removed:a", "!watched:a"),
            "orphans" to setOf("!dm:a", "!dm-muted:a", "!orphan:a", "!favourite:a", "!low:a", "!removed:a", "!watched:a"),
            "invites" to setOf("!invited:a"),
            "every membership" to setOf("!dm:a", "!dm-muted:a", "!room:a", "!room-in-two:a", "!orphan:a", "!invited:a",
                    "!left:a", "!favourite:a", "!low:a", "!removed:a", "!watched:a"),
            "with notifications" to setOf("!dm:a", "!room:a", "!room-in-two:a", "!orphan:a", "!invited:a", "!left:a",
                    "!favourite:a", "!low:a", "!removed:a", "!watched:a"),
            "favourites" to setOf("!favourite:a"),
            "not low priority" to setOf("!dm:a", "!dm-muted:a", "!room:a", "!room-in-two:a", "!orphan:a", "!invited:a",
                    "!left:a", "!favourite:a", "!removed:a", "!watched:a"),
            "removed from room" to setOf("!removed:a"),
            "not removed from room" to setOf("!dm:a", "!dm-muted:a", "!room:a", "!room-in-two:a", "!orphan:a",
                    "!invited:a", "!left:a", "!favourite:a", "!low:a", "!watched:a"),
            "watched" to setOf("!watched:a"),
            "spaces" to setOf("!space:a"),
            "tagged u.work" to setOf("!room:a", "!room-in-two:a"),
            "active tag filter u.personal" to setOf("!room-in-two:a", "!orphan:a"),
            "untagged catch-all" to setOf("!dm:a", "!dm-muted:a", "!favourite:a", "!low:a", "!removed:a", "!watched:a"),
            "tagged and not excluded" to setOf("!room:a"),
    )

    @Test
    fun `each query shape selects exactly the expected rooms`() {
        expectations.forEach { (name, expected) ->
            val params = queries.getValue(name)
            val matched = rooms.filter {
                it.matches(params, taggedRoomIds(params.activeTagFilter), taggedRoomIds(params.hasTag), excludedTagRoomIds(params))
            }
            assertEquals(name, expected, matched.map { it.room_id }.toSet())
        }
    }

    @Test
    fun `counts are the sum over the matching rooms`() {
        expectations.forEach { (name, expected) ->
            val params = queries.getValue(name)
            val matched = rooms.filter {
                it.matches(params, taggedRoomIds(params.activeTagFilter), taggedRoomIds(params.hasTag), excludedTagRoomIds(params))
            }
            assertEquals("$name: notification count",
                    rooms.filter { it.room_id in expected }.sumOf { it.notification_count },
                    matched.sumOf { it.notification_count })
            assertEquals("$name: highlight count",
                    rooms.filter { it.room_id in expected }.sumOf { it.highlight_count },
                    matched.sumOf { it.highlight_count })
        }
    }

    @Test
    fun `every query shape is pinned by an expectation`() {
        assertEquals(queries.keys, expectations.keys)
    }

    @Suppress("LongParameterList")
    private fun room(
            roomId: String,
            roomType: String? = null,
            membership: String = "JOIN",
            isDirect: Boolean = false,
            notification: Long = 0,
            highlight: Long = 0,
            parents: String? = null,
            hidden: Boolean = false,
            favourite: Boolean = false,
            lowPriority: Boolean = false,
            removed: Boolean = false,
            watched: Boolean = false,
    ) = RoomSummaryRow(
            room_id = roomId,
            room_type = roomType,
            display_name = roomId,
            normalized_display_name = roomId,
            avatar_url = null,
            name = null,
            topic = null,
            topic_formatted = null,
            latest_previewable_event_id = null,
            last_activity_time = null,
            heroes = "",
            joined_members_count = null,
            invited_members_count = null,
            is_direct = isDirect.toFlag(),
            direct_user_id = null,
            other_member_ids = "",
            notification_count = notification,
            highlight_count = highlight,
            thread_notification_count = 0,
            thread_highlight_count = 0,
            read_marker_id = null,
            has_unread_messages = 0,
            marked_unread = 0,
            is_favourite = favourite.toFlag(),
            is_low_priority = lowPriority.toFlag(),
            is_server_notice = 0,
            breadcrumbs_index = -1,
            canonical_alias = null,
            aliases = "",
            flat_aliases = "",
            is_encrypted = 0,
            e2e_algorithm = null,
            encryption_event_ts = null,
            room_encryption_trust_level_str = null,
            inviter_id = null,
            direct_user_presence_user_id = null,
            has_failed_sending = 0,
            flatten_parent_ids = parents,
            membership_str = membership,
            is_hidden_from_user = hidden.toFlag(),
            versioning_state_str = "NONE",
            join_rules_str = null,
            direct_parent_names = "",
            is_removed_from_room = removed.toFlag(),
            is_watched = watched.toFlag(),
    )

    private fun taggedRoomIds(tag: String?): Set<String>? =
            tag?.let { wanted -> tags.filterValues { wanted in it }.keys }

    private fun excludedTagRoomIds(params: RoomSummaryQueryParams): Set<String>? =
            params.excludeTags.takeIf { it.isNotEmpty() }
                    ?.let { excluded -> tags.filterValues { it.any(excluded::contains) }.keys }

    private fun Boolean.toFlag() = if (this) 1L else 0L
}
