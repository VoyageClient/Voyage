/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.pushrules.Action
import org.matrix.android.sdk.api.session.pushrules.EventMatchCondition
import org.matrix.android.sdk.api.session.pushrules.EventPropertyContainsCondition
import org.matrix.android.sdk.api.session.pushrules.EventPropertyIsCondition
import org.matrix.android.sdk.api.session.pushrules.RuleIds
import org.matrix.android.sdk.api.session.pushrules.RuleKind
import org.matrix.android.sdk.api.session.pushrules.getActions
import org.matrix.android.sdk.api.session.pushrules.RuleScope
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.create.getRoomCreateContentWithSender
import org.matrix.android.sdk.api.session.room.notification.RoomNotificationState
import org.matrix.android.sdk.api.session.room.powerlevels.RoomPowerLevels
import org.matrix.android.sdk.api.session.room.read.ReadService
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.mapper.PushRulesMapper
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.session.room.notification.toRoomNotificationState
import org.matrix.android.sdk.internal.session.room.notification.toRoomPushRule
import org.matrix.android.sdk.internal.database.sql.TimelineEventQueries

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

// The escaped paths the two mention push rules are defined with.
private const val MENTIONS_USER_IDS_PATH = "content.m\\.mentions.user_ids"
private const val MENTIONS_ROOM_PATH = "content.m\\.mentions.room"

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
 * ones that notify. Stands in for the counts under sliding sync, which Synapse always answers as 0.
 *
 * Decided by the account's own push rules — the two mention rules and the keyword rules, each only
 * while enabled — so a muted room stays silent and a mentions-only room counts just what would have
 * notified. Only the locally held timeline is visible here, so a room with a long unseen backlog reads
 * low rather than wrong.
 */
internal fun SessionStores.localUnreadCounts(userId: String, roomId: String): LocalUnreadCounts {
    val notificationState = roomNotificationState(roomId) ?: return LocalUnreadCounts(0, 0)
    val window = unreadWindow(userId, roomId) ?: return LocalUnreadCounts(0, 0)

    // Highlighted events, and those that only notify (a keyword rule whose actions carry no highlight).
    val highlighted = mutableSetOf<String>()
    val notified = mutableSetOf<String>()
    val powerLevels by lazy { roomPowerLevels(roomId) }
    val userMentions = EventPropertyContainsCondition(MENTIONS_USER_IDS_PATH, userId)
            .takeIf { isRuleEnabled(RuleIds.RULE_ID_IS_USER_MENTION) }
    val roomMentions = EventPropertyIsCondition(MENTIONS_ROOM_PATH, true)
            .takeIf { isRuleEnabled(RuleIds.RULE_ID_IS_ROOM_MENTION) }

    window.mentionCandidates().forEach { event ->
        val mentioned = userMentions?.isSatisfied(event) == true ||
                (roomMentions?.isSatisfied(event) == true &&
                        event.senderId?.let { powerLevels.isUserAbleToTriggerNotification(it, PowerLevelsContent.NOTIFICATIONS_ROOM_KEY) } == true)
        if (mentioned) highlighted.add(event.eventId.orEmpty())
    }

    keywordRules().forEach { keyword ->
        window.keywordCandidates(keyword.pattern)
                .filter { keyword.condition.isSatisfied(it) }
                .forEach { event ->
                    val eventId = event.eventId.orEmpty()
                    if (keyword.highlights) highlighted.add(eventId) else notified.add(eventId)
                }
    }

    // A mentions-only room notifies for what these rules matched and nothing else.
    val notifying = if (notificationState == RoomNotificationState.MENTIONS_ONLY) {
        (highlighted + notified).size
    } else {
        window.messageCount()
    }
    return LocalUnreadCounts(notificationCount = notifying, highlightCount = highlighted.size)
}

/**
 * The slice of the live chunk past our read receipt. No receipt at all means nothing in the room has
 * been read, so everything it holds counts; a receipt on an event this chunk never received has no
 * index to compare against, so that falls back to time as isEventRead does.
 */
private class UnreadWindow(
        private val queries: TimelineEventQueries,
        private val chunkId: Long,
        private val userId: String,
        private val displayIndex: Long?,
        private val timestamp: Long,
) {

    fun messageCount(): Int = if (displayIndex != null) {
        queries.countUnreadInChunkAfterIndex(chunkId, displayIndex, userId, UNREAD_COUNTABLE_TYPES)
    } else {
        queries.countUnreadInChunkAfterTs(chunkId, timestamp, userId, UNREAD_COUNTABLE_TYPES)
    }.executeAsOne().toInt()

    fun mentionCandidates(): List<Event> = if (displayIndex != null) {
        queries.selectMentionCandidatesInChunkAfterIndex(chunkId, displayIndex, userId, UNREAD_COUNTABLE_TYPES)
    } else {
        queries.selectMentionCandidatesInChunkAfterTs(chunkId, timestamp, userId, UNREAD_COUNTABLE_TYPES)
    }.executeAsList().map { it.toEntity().asDomain() }

    /** Narrowed by a substring of the keyword; the rule's own matcher has the final say. */
    fun keywordCandidates(keyword: String): List<Event> {
        val pattern = "%${keyword.globToSqlLike()}%"
        return if (displayIndex != null) {
            queries.selectKeywordCandidatesInChunkAfterIndex(chunkId, displayIndex, userId, UNREAD_COUNTABLE_TYPES, pattern, pattern)
        } else {
            queries.selectKeywordCandidatesInChunkAfterTs(chunkId, timestamp, userId, UNREAD_COUNTABLE_TYPES, pattern, pattern)
        }.executeAsList().map { it.toEntity().asDomain() }
    }
}

private fun SessionStores.unreadWindow(userId: String, roomId: String): UnreadWindow? {
    val liveChunkId = chunk.lastForward(roomId)?.id ?: return null
    val receipt = readReceipt.getReceipt(roomId, userId, ReadService.THREAD_ID_MAIN)
    val rrIndex = receipt?.let { timelineEvent.getInChunkByEventId(liveChunkId, it.eventId)?.displayIndex }
    return UnreadWindow(
            queries = database.timelineEventQueries,
            chunkId = liveChunkId,
            userId = userId,
            displayIndex = when {
                receipt == null -> Long.MIN_VALUE
                else -> rrIndex?.toLong()
            },
            timestamp = receipt?.originServerTs?.toLong() ?: 0L,
    )
}

private class KeywordRule(val pattern: String, val highlights: Boolean) {
    val condition = EventMatchCondition("content.body", pattern)
}

/** The user's own keyword rules, which live in the content ruleset and match the message body. */
private fun SessionStores.keywordRules(): List<KeywordRule> =
        pushRules.get(RuleScope.GLOBAL, RuleKind.CONTENT)?.pushRules.orEmpty()
                // A keyword is any content rule the user added: the spec's own start with a dot.
                .filter { it.enabled && !it.pattern.isNullOrBlank() && !it.ruleId.startsWith(".") }
                .map { entity ->
                    KeywordRule(
                            pattern = entity.pattern.orEmpty(),
                            highlights = PushRulesMapper.mapContentRule(entity).getActions()
                                    .any { it is Action.Highlight && it.highlight },
                    )
                }

private fun SessionStores.isRuleEnabled(ruleId: String): Boolean =
        pushRules.findRule(RuleScope.GLOBAL, ruleId)?.second?.enabled ?: true

private fun SessionStores.roomPowerLevels(roomId: String): RoomPowerLevels = RoomPowerLevels(
        currentStateEvent.getOne(roomId, EventType.STATE_ROOM_POWER_LEVELS, "")?.root
                ?.let { ContentMapper.map(it.content) }.toModel<PowerLevelsContent>(),
        currentStateEvent.getOne(roomId, EventType.STATE_ROOM_CREATE, "")?.root?.asDomain()?.getRoomCreateContentWithSender(),
)

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
