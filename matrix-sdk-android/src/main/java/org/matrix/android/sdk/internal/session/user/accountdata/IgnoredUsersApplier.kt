/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.user.accountdata

import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sql.store.localUnreadCounts
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.room.relation.ReactionSummaryRefresher
import org.matrix.android.sdk.internal.session.room.summary.SqlRoomSummaryUpdater
import javax.inject.Inject

/**
 * Brings the ignore list, and everything derived from it, to a given set of ids. Runs for both the
 * synced `m.ignored_user_list` and our own update, which applies it without waiting to be told.
 *
 * Returns the users no longer ignored, whose withheld content the caller then has to recover.
 */
internal class IgnoredUsersApplier @Inject constructor(
        @UserId private val userId: String,
        private val roomSummaryUpdater: SqlRoomSummaryUpdater,
        private val reactionSummaryRefresher: ReactionSummaryRefresher,
) {

    fun apply(stores: SessionStores, newIgnoredUserIds: Collection<String>): List<String> {
        val currentIgnoredUserIds = stores.user.getIgnoredUserIds()
        val newlyUnIgnored = currentIgnoredUserIds.filter { it !in newIgnoredUserIds }
        val newlyIgnored = newIgnoredUserIds.filter { it !in currentIgnoredUserIds }
        currentIgnoredUserIds.forEach { stores.user.deleteIgnoredUser(it) }
        newIgnoredUserIds.forEach { stores.user.insertIgnoredUser(it) }
        val changedUsers = newlyIgnored + newlyUnIgnored
        if (changedUsers.isEmpty()) return newlyUnIgnored

        // Re-evaluate the room-list preview and hide/show inbound invites for the changed users. Writing
        // room_summary here is what makes this work regardless of which client did the (un)ignore: the
        // room list observes room_summary, but NOT the ignored_user table. (The open timeline re-filters
        // itself separately via the ignored_user flow.)
        //
        // Drop a newly-ignored author's message wherever it is the current preview (keyed on the stored
        // preview's author, so it doesn't depend on possibly-stale other_member_ids), plus re-evaluate
        // rooms a changed user belongs to (un-ignore may restore their message as the preview).
        val roomsToRefresh = LinkedHashSet<String>()
        roomsToRefresh += stores.roomSummary.roomIdsWithPreviewFromSenders(newlyIgnored)
        roomsToRefresh += stores.roomSummary.roomIdsWithActiveMembers(changedUsers)
        // Under sliding sync the unread counts are ours to compute, so they have to be redone too —
        // the server will not restate them, and the room's next sync could be a long way off.
        val recountUnread = stores.syncToken.getSlidingSyncPos() != null
        roomsToRefresh.forEach { roomId ->
            roomSummaryUpdater.refreshLatestPreviewableEvent(stores, roomId, clearIfNone = true)
            if (recountUnread) {
                val local = stores.localUnreadCounts(userId, roomId)
                stores.roomSummary.setUnreadCounters(roomId, local.notificationCount, local.highlightCount)
            }
        }
        // Inbound invites: hide those from a newly-ignored inviter, reveal them again on un-ignore.
        stores.roomSummary.inviteRoomIdsByInviters(newlyIgnored).forEach { stores.roomSummary.setHiddenFromUser(it, true) }
        stores.roomSummary.inviteRoomIdsByInviters(newlyUnIgnored).forEach { stores.roomSummary.setHiddenFromUser(it, false) }
        // Their reactions are counted from stored events, so the counts have to be redone by hand.
        reactionSummaryRefresher.refreshFromSenders(stores, changedUsers)
        return newlyUnIgnored
    }
}
