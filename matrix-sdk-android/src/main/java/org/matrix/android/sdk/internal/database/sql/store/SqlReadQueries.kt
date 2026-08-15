/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.pushrules.RuleIds
import org.matrix.android.sdk.api.session.pushrules.RuleScope
import org.matrix.android.sdk.api.session.room.notification.RoomNotificationState
import org.matrix.android.sdk.api.session.room.read.ReadService
import org.matrix.android.sdk.internal.session.room.notification.toRoomNotificationState
import org.matrix.android.sdk.internal.session.room.notification.toRoomPushRule

/** SQL replacements for the Realm-based ReadQueries (isEventRead / isReadMarkerMoreRecent) + latest synced event. */

internal fun SessionStores.latestSyncedEventId(roomId: String): String? =
        chunk.lastForward(roomId)?.id?.let { chunkId ->
            timelineEvent.getByChunk(chunkId).maxByOrNull { it.displayIndex }?.eventId
        }

// Mirrors the Realm ReadQueries semantics: local echoes and own events are read by definition, an
// event outside the live chunk is older than the sync window (so read), and otherwise the receipt
// must sit at or past the event within the live chunk — displayIndex is per-chunk, so comparing it
// across chunks (e.g. after a gappy sync started a fresh chunk) would be meaningless.
internal fun SessionStores.isEventRead(userId: String, roomId: String, eventId: String): Boolean {
    if (LocalEcho.isLocalEchoId(eventId)) return true
    val liveChunkId = chunk.lastForward(roomId)?.id ?: return false
    val eventToCheck = timelineEvent.getInChunkByEventId(liveChunkId, eventId)
    return when {
        eventToCheck == null -> true
        eventToCheck.root?.sender == userId -> true
        else -> {
            val receipt = readReceipt.getReceipt(roomId, userId, ReadService.THREAD_ID_MAIN) ?: return false
            val rrIndex = timelineEvent.getInChunkByEventId(liveChunkId, receipt.eventId)?.displayIndex
            when {
                rrIndex != null -> eventToCheck.displayIndex <= rrIndex
                // The receipt points at an event we were never sent — sliding sync delivers only the newest
                // few per room — so there is no index to compare against. Falling through to "unread" there
                // marks rooms the user has plainly read as unread, so compare when each happened instead.
                else -> (eventToCheck.root?.originServerTs ?: 0L) <= receipt.originServerTs.toLong()
            }
        }
    }
}

// Event types that count as something to read. Reactions and state changes are deliberately absent:
// they are not messages, and counting them makes rooms look unread for activity nobody reads.
private val UNREAD_COUNTABLE_TYPES = listOf(
        EventType.MESSAGE,
        EventType.ENCRYPTED,
        EventType.STICKER,
) + EventType.POLL_START.values

/** Both counts a sliding-sync connection cannot get from the server. */
internal data class LocalUnreadCounts(val notificationCount: Int, val highlightCount: Int)

/**
 * What is unread in the live chunk past our read receipt: messages from other people, and of those the
 * ones that name us. Stands in for the counts under sliding sync, which Synapse always answers as 0.
 *
 * Follows the account's push rules, so a muted room stays silent and a mentions-only room counts only
 * its mentions — the same answers sync v2 gives. Two narrower limits than the server's own evaluation:
 * only the locally held timeline is visible, so a room with a long unseen backlog reads low rather than
 * wrong; and a mention has to name the user id (m.mentions or a permalink), so @room and a bare
 * display-name match do not highlight.
 */
internal fun SessionStores.localUnreadCounts(userId: String, roomId: String): LocalUnreadCounts {
    val notificationState = roomNotificationState(roomId) ?: return LocalUnreadCounts(0, 0)
    val liveChunkId = chunk.lastForward(roomId)?.id ?: return LocalUnreadCounts(0, 0)
    val queries = database.timelineEventQueries
    val mentionPattern = "%${userId.globToSqlLike()}%"

    // No receipt at all means nothing in the room has been read, so everything it holds counts.
    val receipt = readReceipt.getReceipt(roomId, userId, ReadService.THREAD_ID_MAIN)
    val rrIndex = receipt?.let { timelineEvent.getInChunkByEventId(liveChunkId, it.eventId)?.displayIndex }
    val (messages, highlights) = if (receipt == null || rrIndex != null) {
        val index = rrIndex?.toLong() ?: Long.MIN_VALUE
        queries.countUnreadInChunkAfterIndex(liveChunkId, index, userId, UNREAD_COUNTABLE_TYPES).executeAsOne() to
                queries.countHighlightsInChunkAfterIndex(liveChunkId, index, userId, UNREAD_COUNTABLE_TYPES, mentionPattern, mentionPattern)
                        .executeAsOne()
    } else {
        // The receipt points at an event this chunk never received; compare by time instead, as isEventRead does.
        val ts = receipt.originServerTs.toLong()
        queries.countUnreadInChunkAfterTs(liveChunkId, ts, userId, UNREAD_COUNTABLE_TYPES).executeAsOne() to
                queries.countHighlightsInChunkAfterTs(liveChunkId, ts, userId, UNREAD_COUNTABLE_TYPES, mentionPattern, mentionPattern)
                        .executeAsOne()
    }
    // A mentions-only room notifies for its mentions and nothing else.
    val notifying = if (notificationState == RoomNotificationState.MENTIONS_ONLY) highlights else messages
    return LocalUnreadCounts(notificationCount = notifying.toInt(), highlightCount = highlights.toInt())
}

// Null where the account would never have notified at all: notifications off for the account (the
// master rule is enabled when they are), or a muted room.
private fun SessionStores.roomNotificationState(roomId: String): RoomNotificationState? {
    val masterRule = pushRules.findRule(RuleScope.GLOBAL, RuleIds.RULE_ID_DISABLE_ALL)?.second
    if (masterRule?.enabled == true) return null
    val state = pushRules.findRule(RuleScope.GLOBAL, roomId)
            ?.let { (kind, entity) -> entity.toRoomPushRule(kind) }
            ?.toRoomNotificationState()
            ?: RoomNotificationState.ALL_MESSAGES
    return state.takeUnless { it == RoomNotificationState.MUTE }
}

internal fun SessionStores.isReadMarkerMoreRecent(roomId: String, eventId: String): Boolean {
    val currentMarker = readMarker.get(roomId) ?: return false
    val markerTimelineEvent = timelineEvent.getByRoomAndEventId(roomId, currentMarker)
    val targetTimelineEvent = timelineEvent.getByRoomAndEventId(roomId, eventId)
    return markerTimelineEvent != null && targetTimelineEvent != null && markerTimelineEvent.displayIndex >= targetTimelineEvent.displayIndex
}
