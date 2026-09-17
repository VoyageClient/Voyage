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
import org.matrix.android.sdk.api.session.events.model.getRootThreadEventId
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.pushrules.Action
import org.matrix.android.sdk.api.session.pushrules.EventMatchCondition
import org.matrix.android.sdk.api.session.pushrules.EventPropertyContainsCondition
import org.matrix.android.sdk.api.session.pushrules.EventPropertyIsCondition
import org.matrix.android.sdk.api.session.pushrules.RuleIds
import org.matrix.android.sdk.api.session.pushrules.RuleKind
import org.matrix.android.sdk.api.session.pushrules.RuleScope
import org.matrix.android.sdk.api.session.pushrules.getActions
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.create.getRoomCreateContentWithSender
import org.matrix.android.sdk.api.session.room.notification.RoomNotificationState
import org.matrix.android.sdk.api.session.room.powerlevels.RoomPowerLevels
import org.matrix.android.sdk.api.session.room.read.ReadService
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.mapper.PushRulesMapper
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.sql.TimelineEventQueries
import org.matrix.android.sdk.internal.session.room.notification.toRoomNotificationState
import org.matrix.android.sdk.internal.session.room.notification.toRoomPushRule

/** SQL replacements for the Realm-based ReadQueries (isEventRead / isReadMarkerMoreRecent) + latest synced event. */

internal fun SessionStores.latestSyncedEventId(roomId: String): String? =
        chunk.lastForward(roomId)?.id?.let { chunkId ->
            timelineEvent.getByChunkNewest(chunkId, limit = 1).firstOrNull()?.eventId
        }

// Local echoes and own events are read by definition. Otherwise the receipt must sit at or past the
// event in the room's order, which is (ts, event_id).
internal fun SessionStores.isEventRead(userId: String, roomId: String, eventId: String, threadId: String? = null): Boolean {
    if (LocalEcho.isLocalEchoId(eventId)) return true
    val eventToCheck = timelineEvent.getByRoomAndEventId(roomId, eventId) ?: return false
    if (eventToCheck.root?.sender == userId) return true
    val receipt = readReceipt.getReceipt(roomId, userId, threadId ?: ReadService.THREAD_ID_MAIN) ?: return false
    val receiptRow = timelineEvent.getByRoomAndEventId(roomId, receipt.eventId)
    return when {
        receiptRow != null -> eventToCheck.ts < receiptRow.ts ||
                (eventToCheck.ts == receiptRow.ts && eventToCheck.eventId <= receiptRow.eventId)
        // Sliding sync may omit the receipt event; use its timestamp when no stored row can be compared.
        else -> (eventToCheck.root?.originServerTs ?: 0L) <= receipt.originServerTs.toLong()
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
 *
 * With threads on, a reply inside a thread is not shown in the room, so it does not make the room
 * unread by itself — but one that mentions us or hits a keyword still notifies, and so still counts.
 */
internal fun SessionStores.localUnreadCounts(userId: String, roomId: String, threadsEnabled: Boolean): LocalUnreadCounts {
    val facts = accountPushFacts()
    val notificationState = roomNotificationState(roomId, facts) ?: return LocalUnreadCounts(0, 0)
    val window = unreadWindow(userId, roomId) ?: return LocalUnreadCounts(0, 0)

    // Highlighted events, and those that only notify (a keyword rule whose actions carry no highlight).
    val highlighted = mutableSetOf<String>()
    val notified = mutableSetOf<String>()
    // Matched events the message count leaves out, so they are added back to it below.
    val matchedInThread = mutableSetOf<String>()
    val powerLevels by lazy { roomPowerLevels(roomId) }
    val userMentions = EventPropertyContainsCondition(MENTIONS_USER_IDS_PATH, userId).takeIf { facts.userMentionEnabled }
    val roomMentions = EventPropertyIsCondition(MENTIONS_ROOM_PATH, true).takeIf { facts.roomMentionEnabled }

    window.mentionCandidates().forEach { event ->
        val mentioned = userMentions?.isSatisfied(event) == true ||
                (roomMentions?.isSatisfied(event) == true &&
                        event.senderId?.let { powerLevels.isUserAbleToTriggerNotification(it, PowerLevelsContent.NOTIFICATIONS_ROOM_KEY) } == true)
        if (mentioned) {
            highlighted.add(event.eventId.orEmpty())
            if (event.getRootThreadEventId() != null) matchedInThread.add(event.eventId.orEmpty())
        }
    }

    facts.keywordRules.forEach { keyword ->
        window.keywordCandidates(keyword.pattern)
                .filter { keyword.condition.isSatisfied(it) }
                .forEach { event ->
                    val eventId = event.eventId.orEmpty()
                    if (keyword.highlights) highlighted.add(eventId) else notified.add(eventId)
                    if (event.getRootThreadEventId() != null) matchedInThread.add(eventId)
                }
    }

    // A mentions-only room notifies for what these rules matched and nothing else.
    val notifying = if (notificationState == RoomNotificationState.MENTIONS_ONLY) {
        (highlighted + notified).size
    } else {
        window.messageCount(countThreads = !threadsEnabled) + if (threadsEnabled) matchedInThread.size else 0
    }
    return LocalUnreadCounts(notificationCount = notifying, highlightCount = highlighted.size)
}

/**
 * The slice of the live chunk past our read receipt, bounded by the order itself — `(ts, event_id)`. No
 * receipt at all means nothing in the room has been read, so everything the chunk holds counts.
 */
private class UnreadWindow(
        private val queries: TimelineEventQueries,
        private val chunkId: Long,
        /** Us, plus everyone we ignore: their stored messages are hidden, so they must not count. */
        private val excludedSenders: Collection<String>,
        private val readTs: Long,
        private val readEventId: String,
) {

    fun messageCount(countThreads: Boolean): Int = queries
            .countUnreadInChunkAfterTs(
                    chunkId, readTs, readTs, readEventId, excludedSenders, UNREAD_COUNTABLE_TYPES, if (countThreads) 1L else 0L
            )
            .executeAsOne().toInt()

    fun mentionCandidates(): List<Event> = queries
            .selectMentionCandidatesInChunkAfterTs(chunkId, readTs, readTs, readEventId, excludedSenders, UNREAD_COUNTABLE_TYPES)
            .executeAsList().map { it.toEntity().asDomain() }

    /** Narrowed by a substring of the keyword; the rule's own matcher has the final say. */
    fun keywordCandidates(keyword: String): List<Event> {
        val pattern = "%${keyword.globToSqlLike()}%"
        return queries
                .selectKeywordCandidatesInChunkAfterTs(
                        chunkId, readTs, readTs, readEventId, excludedSenders, UNREAD_COUNTABLE_TYPES, pattern, pattern
                )
                .executeAsList().map { it.toEntity().asDomain() }
    }
}

private fun SessionStores.unreadWindow(userId: String, roomId: String): UnreadWindow? {
    val liveChunkId = chunk.lastForward(roomId)?.id ?: return null
    val receipt = readReceipt.getReceipt(roomId, userId, ReadService.THREAD_ID_MAIN)
    return UnreadWindow(
            queries = database.timelineEventQueries,
            chunkId = liveChunkId,
            excludedSenders = user.getIgnoredUserIds() + userId,
            // No receipt means nothing in the room has been read, so everything it holds counts.
            readTs = receipt?.originServerTs?.toLong() ?: Long.MIN_VALUE,
            readEventId = receipt?.eventId.orEmpty(),
    )
}

private class KeywordRule(val pattern: String, val highlights: Boolean) {
    val condition = EventMatchCondition("content.body", pattern)
}

/** Cache room-independent push facts across rooms until the ruleset version changes. */
private class AccountPushFacts(
        val version: Long,
        val notificationsDisabled: Boolean,
        val keywordRules: List<KeywordRule>,
        val userMentionEnabled: Boolean,
        val roomMentionEnabled: Boolean,
)

// Weak keys: a signed-out session's store must not be kept alive by its cached facts.
private val accountPushFacts = java.util.Collections.synchronizedMap(java.util.WeakHashMap<PushRulesSqlStore, AccountPushFacts>())

private fun SessionStores.accountPushFacts(): AccountPushFacts {
    val version = pushRules.version
    accountPushFacts[pushRules]?.takeIf { it.version == version }?.let { return it }
    val facts = AccountPushFacts(
            version = version,
            notificationsDisabled = pushRules.findRule(RuleScope.GLOBAL, RuleIds.RULE_ID_DISABLE_ALL)?.second?.enabled == true,
            keywordRules = keywordRules(),
            userMentionEnabled = isRuleEnabled(RuleIds.RULE_ID_IS_USER_MENTION),
            roomMentionEnabled = isRuleEnabled(RuleIds.RULE_ID_IS_ROOM_MENTION),
    )
    accountPushFacts[pushRules] = facts
    return facts
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
private fun SessionStores.roomNotificationState(roomId: String, facts: AccountPushFacts): RoomNotificationState? {
    if (facts.notificationsDisabled) return null
    val state = pushRules.findRule(RuleScope.GLOBAL, roomId)
            ?.let { (kind, entity) -> entity.toRoomPushRule(kind) }
            ?.toRoomNotificationState()
            ?: RoomNotificationState.ALL_MESSAGES
    return state.takeUnless { it == RoomNotificationState.MUTE }
}

internal fun SessionStores.isReadMarkerMoreRecent(roomId: String, eventId: String): Boolean {
    val currentMarker = readMarker.get(roomId) ?: return false
    if (currentMarker == eventId) return true
    val marker = timelineEvent.getByRoomAndEventId(roomId, currentMarker) ?: return false
    val target = timelineEvent.getByRoomAndEventId(roomId, eventId) ?: return false

    return if (marker.ts != target.ts) marker.ts > target.ts else marker.eventId >= target.eventId
}
