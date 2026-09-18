/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.mentions

import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.eventindex.EventIndexService
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.pushrules.EventMatchCondition
import org.matrix.android.sdk.api.session.pushrules.EventPropertyContainsCondition
import org.matrix.android.sdk.api.session.pushrules.EventPropertyIsCondition
import org.matrix.android.sdk.api.session.pushrules.RuleIds
import org.matrix.android.sdk.api.session.pushrules.RuleKind
import org.matrix.android.sdk.api.session.pushrules.RuleScope
import org.matrix.android.sdk.api.session.room.mentions.MentionEvent
import org.matrix.android.sdk.api.session.room.mentions.MentionKind
import org.matrix.android.sdk.api.session.room.mentions.MentionsQueryParams
import org.matrix.android.sdk.api.session.room.mentions.MentionsResult
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.create.getRoomCreateContentWithSender
import org.matrix.android.sdk.api.session.room.powerlevels.RoomPowerLevels
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sql.store.globToSqlLike
import org.matrix.android.sdk.internal.database.sql.store.toEntity
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.pushers.PushRulesApi
import org.matrix.android.sdk.internal.session.search.index.EventIndexStore
import org.matrix.android.sdk.internal.session.search.index.IndexedRow
import javax.inject.Inject

/** The escaped paths the two mention push rules are defined with. */
private const val MENTIONS_USER_IDS_PATH = "content.m\\.mentions.user_ids"
private const val MENTIONS_ROOM_PATH = "content.m\\.mentions.room"

private const val ROOM_MENTION_LIKE = "%\"room\":true%"

/** A literal needle for a `LIKE ? ESCAPE '\'` comparison. */
private fun String.escapeForSqlLike(): String =
        "%" + replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

private val eventAdapter = org.matrix.android.sdk.internal.di.MoshiProvider.providesMoshi().adapter(Event::class.java)

/** How many empty pages to read through before giving up, so a long dry run cannot hang the screen. */
private const val MAX_SERVER_PAGES = 5

/**
 * The local sources are read to their own depth rather than the server's page size: `mention_hit` is a
 * small indexed table, so taking only a page of it would cut local history short for no gain.
 */
private const val LOCAL_LIMIT = 1_000

private val MENTIONABLE_TYPES = listOf(
        EventType.MESSAGE,
        EventType.ENCRYPTED,
        EventType.STICKER,
) + EventType.POLL_START.values

/**
 * Everything across the account's joined rooms that mentions us, newest first, decided by the same
 * push rules the notifications use: the two `m.mentions` rules and the user's keyword rules, each only
 * while enabled.
 *
 * Three sources, unioned and deduplicated by event id: the synced timeline, the local search index
 * (which the crawler fills with history sync never received), and the server's own `/notifications`.
 * The server is the only one that pages and the only one that sees what arrived while the client was
 * offline; the local two are the only ones that work without a network, or at all for encrypted rooms,
 * whose `m.mentions` the server cannot read.
 */
internal class MentionsFinder @Inject constructor(
        @UserId private val userId: String,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val indexStore: EventIndexStore,
        private val eventIndexService: Lazy<EventIndexService>,
        private val pushRulesApi: PushRulesApi,
        private val globalErrorReceiver: GlobalErrorReceiver,
) {

    suspend fun getMentions(params: MentionsQueryParams): MentionsResult {
        val joinedRooms = withContext(dispatcher) { stores.roomSummary.getRoomIdsByMembership(Membership.JOIN).toSet() }
        if (joinedRooms.isEmpty()) return MentionsResult(emptyList())
        // Only the server pages; a continuation therefore reads it alone, since the local sources
        // already contributed everything they hold to the first page.
        val continuation = params.from != null
        val excludedSenders = withContext(dispatcher) { stores.user.getIgnoredUserIds() } + userId
        // An event several candidate queries return keeps its first, strongest classification.
        val hits = LinkedHashMap<String, MentionEvent>()

        val userMentions = EventPropertyContainsCondition(MENTIONS_USER_IDS_PATH, userId)
                .takeIf { isRuleEnabled(RuleIds.RULE_ID_IS_USER_MENTION) }
        val roomMentions = EventPropertyIsCondition(MENTIONS_ROOM_PATH, true)
                .takeIf { params.includeRoomMentions && isRuleEnabled(RuleIds.RULE_ID_IS_ROOM_MENTION) }

        // The index keeps its own table of what mentions us, so one read serves both kinds.
        val indexed = if (!continuation && (userMentions != null || roomMentions != null)) {
            indexCandidates(excludedSenders, joinedRooms) { indexStore.mentionHits(LOCAL_LIMIT) }
        } else {
            emptyList()
        }
        val page = if (userMentions != null || roomMentions != null) {
            serverPage(params, excludedSenders, joinedRooms)
        } else {
            ServerPage(emptyList(), null)
        }
        val notified = page.events

        if (userMentions != null) {
            // Narrowed by our user id rather than by `m.mentions`: an empty `"m.mentions":{}` block
            // rides along on most messages, so that pattern's newest rows are nearly all not mentions.
            (localCandidates(continuation) { sessionCandidates(userId.escapeForSqlLike(), excludedSenders, LOCAL_LIMIT, joinedRooms) } + indexed + notified)
                    .filter { userMentions.isSatisfied(it) }
                    .forEach { hits.addHit(it, MentionKind.USER) }
        }

        if (roomMentions != null) {
            (localCandidates(continuation) { sessionCandidates(ROOM_MENTION_LIKE, excludedSenders, LOCAL_LIMIT, joinedRooms) } + indexed + notified)
                    .filter { roomMentions.isSatisfied(it) && it.canNotifyRoom() }
                    .forEach { hits.addHit(it, MentionKind.ROOM) }
        }

        if (params.includeKeywords) {
            keywordPatterns().forEach { pattern ->
                val condition = EventMatchCondition("content.body", pattern)
                val needle = "%${pattern.globToSqlLike()}%"
                (localCandidates(continuation) { sessionCandidates(needle, excludedSenders, LOCAL_LIMIT, joinedRooms) } +
                        localCandidates(continuation) {
                            indexCandidates(excludedSenders, joinedRooms) { indexStore.byContentLike(needle.lowercase(), LOCAL_LIMIT) }
                        })
                        .filter { condition.isSatisfied(it) }
                        .forEach { hits.addHit(it, MentionKind.KEYWORD) }
            }
        }

        // Not truncated to [limit]: each source is already bounded, and cutting the union would drop
        // the oldest of it while the next page resumes from the server's token, losing the middle.
        return MentionsResult(
                events = hits.values.sortedByDescending { it.originServerTs },
                nextToken = page.nextToken,
        )
    }

    /** A local source, skipped once we are paging the server's history: only it can go further back. */
    private suspend fun localCandidates(continuation: Boolean, source: suspend () -> List<Event>): List<Event> =
            if (continuation) emptyList() else source()

    private class ServerPage(val events: List<Event>, val nextToken: String?)

    /**
     * What the server says notified us (`only=highlight`), which is the only source that sees what
     * arrived while the client was offline and never got synced or crawled. Best-effort: offline, or a
     * server that does not answer, simply leaves the local sources to stand on their own.
     *
     * An encrypted event comes back as ciphertext, since the server cannot read `m.mentions` either, so
     * those are dropped here and left to the local sources, which hold them decrypted.
     */
    private suspend fun serverPage(
            params: MentionsQueryParams,
            excludedSenders: Collection<String>,
            joinedRooms: Set<String>,
    ): ServerPage {
        // A page can filter down to nothing (rooms we have left, edits), which would read as "no more
        // mentions" while the history goes on. Keep reading until something survives or the token ends.
        var from = params.from
        val events = mutableListOf<Event>()
        repeat(MAX_SERVER_PAGES) {
            val response = tryOrNull {
                executeRequest(globalErrorReceiver) {
                    pushRulesApi.getNotifications(from = from, limit = params.limit, only = "highlight")
                }
            } ?: return ServerPage(events, from.takeIf { events.isNotEmpty() })
            events += response.notifications
                    // `/notifications` carries room_id on the notification, not inside the event, so
                    // the event has to be given one or everything downstream reads it as room-less.
                    .mapNotNull { notification ->
                        val event = notification.event ?: return@mapNotNull null
                        val roomId = event.roomId ?: notification.roomId ?: return@mapNotNull null
                        if (event.roomId == null) event.copy(roomId = roomId) else event
                    }
                    .filter { it.roomId in joinedRooms && it.senderId !in excludedSenders }
                    .filter { it.type in MENTIONABLE_TYPES && it.getClearType() != EventType.ENCRYPTED && !it.isReplace() }
            from = response.nextToken
            if (events.isNotEmpty() || from == null) return ServerPage(events, from)
        }
        return ServerPage(events, from)
    }

    private fun MutableMap<String, MentionEvent>.addHit(event: Event, kind: MentionKind) {
        val eventId = event.eventId ?: return
        if (containsKey(eventId)) return
        put(eventId, MentionEvent(
                roomId = event.roomId.orEmpty(),
                eventId = eventId,
                originServerTs = event.originServerTs ?: 0L,
                kind = kind,
                event = event,
        ))
    }

    private suspend fun sessionCandidates(
            like: String,
            excludedSenders: Collection<String>,
            limit: Int,
            joinedRooms: Set<String>,
    ): List<Event> = withContext(dispatcher) {
        stores.database.eventQueries
                .selectContentLikeCandidates(MENTIONABLE_TYPES, excludedSenders, like, like, limit.toLong())
                .executeAsList().map { it.toEntity().asDomain() }
                .filter { it.roomId in joinedRooms }
    }

    /**
     * The same narrowing run against the local search index, whose crawler holds history the session
     * store never received, which is everything that arrived while the client was offline. Skipped when the
     * user has indexing off, in which case only the synced timeline is visible.
     */
    private suspend fun indexCandidates(
            excludedSenders: Collection<String>,
            joinedRooms: Set<String>,
            query: suspend () -> List<IndexedRow>,
    ): List<Event> {
        if (!eventIndexService.get().isEnabled()) return emptyList()
        return query()
                .filter { it.roomId in joinedRooms && it.sender !in excludedSenders }
                .mapNotNull { row -> tryOrNull { eventAdapter.fromJson(row.eventJson) } }
                .filter { it.type in MENTIONABLE_TYPES && !it.isReplace() }
    }

    private fun Event.isReplace(): Boolean =
            (content?.get("m.relates_to") as? Map<*, *>)?.get("rel_type") == "m.replace"

    /** The user's own keyword rules, which live in the content ruleset and match the message body. */
    private fun keywordPatterns(): List<String> =
            stores.pushRules.get(RuleScope.GLOBAL, RuleKind.CONTENT)?.pushRules.orEmpty()
                    // A keyword is any content rule the user added: the spec's own start with a dot.
                    .filter { it.enabled && !it.pattern.isNullOrBlank() && !it.ruleId.startsWith(".") }
                    .mapNotNull { it.pattern }

    private fun isRuleEnabled(ruleId: String): Boolean =
            stores.pushRules.findRule(RuleScope.GLOBAL, ruleId)?.second?.enabled ?: true

    private fun Event.canNotifyRoom(): Boolean {
        val roomId = roomId ?: return false
        val sender = senderId ?: return false
        return roomPowerLevels(roomId).isUserAbleToTriggerNotification(sender, PowerLevelsContent.NOTIFICATIONS_ROOM_KEY)
    }

    private fun roomPowerLevels(roomId: String): RoomPowerLevels = RoomPowerLevels(
            stores.currentStateEvent.getOne(roomId, EventType.STATE_ROOM_POWER_LEVELS, "")?.root
                    ?.let { ContentMapper.map(it.content) }.toModel<PowerLevelsContent>(),
            stores.currentStateEvent.getOne(roomId, EventType.STATE_ROOM_CREATE, "")?.root?.asDomain()?.getRoomCreateContentWithSender(),
    )
}
