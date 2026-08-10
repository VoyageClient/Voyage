/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.peeking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import org.matrix.android.sdk.api.query.QueryStateEventValue
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.getRelationContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.EditAggregatedSummary
import org.matrix.android.sdk.api.session.room.model.EventAnnotationsSummary
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.ReactionAggregatedSummary
import org.matrix.android.sdk.api.session.room.model.RoomAvatarContent
import org.matrix.android.sdk.api.session.room.model.RoomCanonicalAliasContent
import org.matrix.android.sdk.api.session.room.model.RoomJoinRulesContent
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.session.room.model.RoomNameContent
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.RoomTopicContent
import org.matrix.android.sdk.api.session.room.model.create.RoomCreateContent
import org.matrix.android.sdk.api.session.room.model.relation.ReactionContent
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.internal.query.matches

/**
 * In-memory holder for the data of one peeked (world-readable, not joined) room: state events,
 * timeline events with edit/reaction aggregation, and a synthetic RoomSummary. Nothing is
 * persisted. All mutating methods must be called while holding [mutex]; the published
 * StateFlows expose immutable snapshots and are safe to read from anywhere.
 */
internal class PeekedRoomDataSource(
        val roomId: String,
        private val myUserId: String,
) {

    val mutex = Mutex()

    // Concurrent: read by seed()/getTimelineEvent() outside the mutex while the poll loop mutates.
    private val memberContents = java.util.concurrent.ConcurrentHashMap<String, RoomMemberContent>()
    private val stateEventsByKey = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Event>()
    private val eventsById = LinkedHashMap<String, Event>()

    // Latest m.replace edit per target event.
    private val editsByTarget = HashMap<String, Event>()

    // target eventId -> reaction key -> reaction events.
    private val reactionsByTarget = HashMap<String, LinkedHashMap<String, MutableList<Event>>>()

    // relation eventId -> (target eventId, reaction key or null for an edit), to undo on redaction.
    private val relationSourceIndex = HashMap<String, Pair<String, String?>>()

    @Volatile private var roomName: String? = null
    @Volatile private var roomTopic: String? = null
    @Volatile private var roomAvatarUrl: String? = null
    @Volatile private var canonicalAliasContent: RoomCanonicalAliasContent? = null

    val summaryFlow = MutableStateFlow(buildSummary())
    val stateEventsFlow = MutableStateFlow<List<Event>>(emptyList())
    val membersFlow = MutableStateFlow<List<RoomMemberSummary>>(emptyList())

    /** Timeline snapshot, newest-first (index 0 = live edge), matching the Timeline.Listener contract. */
    val timelineFlow = MutableStateFlow<List<TimelineEvent>>(emptyList())

    fun getStateEvent(eventType: String, stateKey: QueryStateEventValue): Event? {
        return getStateEvents(setOf(eventType), stateKey).firstOrNull()
    }

    fun getStateEvents(eventTypes: Set<String>, stateKey: QueryStateEventValue): List<Event> {
        return filterStateEvents(stateEventsFlow.value, eventTypes, stateKey)
    }

    fun filterStateEvents(events: List<Event>, eventTypes: Set<String>, stateKey: QueryStateEventValue): List<Event> {
        return events.filter { event ->
            (eventTypes.isEmpty() || event.type in eventTypes) &&
                    (stateKey as QueryStringValue).matches(event.stateKey)
        }
    }

    // Events known outside the timeline window (e.g. uploads gallery results).
    private val auxEventsById = java.util.concurrent.ConcurrentHashMap<String, Event>()

    fun cacheAuxEvents(events: List<Event>) {
        events.forEach { event -> event.eventId?.let { auxEventsById[it] = event } }
    }

    /** Pre-peek metadata (from the directory / permalink), so the UI has a name and avatar while loading. */
    fun seed(roomName: String?, roomAvatarUrl: String?, roomTopic: String?, roomAlias: String?) {
        var changed = false
        if (this.roomName == null && roomName != null) { this.roomName = roomName; changed = true }
        if (this.roomAvatarUrl == null && roomAvatarUrl != null) { this.roomAvatarUrl = roomAvatarUrl; changed = true }
        if (this.roomTopic == null && roomTopic != null) { this.roomTopic = roomTopic; changed = true }
        if (this.canonicalAliasContent == null && roomAlias != null) {
            this.canonicalAliasContent = RoomCanonicalAliasContent(canonicalAlias = roomAlias)
            changed = true
        }
        if (changed) summaryFlow.value = buildSummary()
    }

    fun getTimelineEvent(eventId: String): TimelineEvent? {
        timelineFlow.value.firstOrNull { it.eventId == eventId }?.let { return it }
        val aux = auxEventsById[eventId] ?: return null
        return TimelineEvent(
                root = aux,
                localId = eventId.hashCode().toLong(),
                eventId = eventId,
                displayIndex = 0,
                senderInfo = senderInfo(aux.senderId),
        )
    }

    /** Single source for the member→SenderInfo mapping so all surfaces disambiguate identically. */
    fun senderInfo(userId: String?): SenderInfo {
        val member = userId?.let { memberContents[it] }
        val duplicated = member?.displayName != null &&
                memberContents.values.count { it.membership == Membership.JOIN && it.displayName == member.displayName } > 1
        return SenderInfo(
                userId = userId.orEmpty(),
                displayName = member?.displayName,
                isUniqueDisplayName = !duplicated,
                avatarUrl = member?.avatarUrl,
        )
    }

    fun getRoomMember(userId: String): RoomMemberSummary? {
        return membersFlow.value.firstOrNull { it.userId == userId }
    }

    fun roomCreateContent(): RoomCreateContent? {
        return getStateEvent(EventType.STATE_ROOM_CREATE, QueryStringValue.IsEmpty)
                ?.content?.toModel<RoomCreateContent>()
    }

    fun applyStateEvent(event: Event, onlyIfAbsent: Boolean = false) {
        val stateKey = event.stateKey ?: return
        val key = (event.type ?: return) to stateKey
        if (onlyIfAbsent && stateEventsByKey.containsKey(key)) return
        stateEventsByKey[key] = event
        when (event.type) {
            EventType.STATE_ROOM_MEMBER ->
                event.content.toModel<RoomMemberContent>()?.let { memberContents[stateKey] = it }
            EventType.STATE_ROOM_NAME ->
                roomName = event.content.toModel<RoomNameContent>()?.name ?: roomName
            EventType.STATE_ROOM_TOPIC ->
                roomTopic = event.content.toModel<RoomTopicContent>()?.topic ?: roomTopic
            EventType.STATE_ROOM_AVATAR ->
                roomAvatarUrl = event.content.toModel<RoomAvatarContent>()?.avatarUrl ?: roomAvatarUrl
            EventType.STATE_ROOM_CANONICAL_ALIAS ->
                canonicalAliasContent = event.content.toModel<RoomCanonicalAliasContent>() ?: canonicalAliasContent
        }
    }

    /**
     * Route [event] into the aggregation structures. Returns the event when it is a plain
     * timeline event the caller should place, or null when it was consumed as a relation
     * or redaction.
     */
    fun ingest(event: Event): Event? {
        val eventId = event.eventId ?: return null
        if (event.getClearType() == EventType.REDACTION) {
            applyRedaction(event.redacts)
            return null
        }
        val relation = event.getRelationContent()
        val targetId = relation?.eventId
        if (targetId != null) {
            when (relation.type) {
                RelationType.ANNOTATION -> {
                    val key = event.content.toModel<ReactionContent>()?.relatesTo?.key ?: return null
                    val byKey = reactionsByTarget.getOrPut(targetId) { LinkedHashMap() }
                    val list = byKey.getOrPut(key) { mutableListOf() }
                    if (list.none { it.eventId == eventId }) {
                        list.add(event)
                        relationSourceIndex[eventId] = targetId to key
                        // The who-reacted sheet resolves reaction source events via getTimelineEvent.
                        auxEventsById[eventId] = event
                    }
                    return null
                }
                RelationType.REPLACE -> {
                    val current = editsByTarget[targetId]
                    if (current == null || (current.originServerTs ?: 0) <= (event.originServerTs ?: 0)) {
                        editsByTarget[targetId] = event
                    }
                    relationSourceIndex[eventId] = targetId to null
                    auxEventsById[eventId] = event
                    return null
                }
            }
        }
        return event
    }

    fun editHistory(eventId: String): List<Event> {
        return listOfNotNull(editsByTarget[eventId], eventsById[eventId])
    }

    fun appendEvent(event: Event) {
        val plain = ingest(event) ?: return
        eventsById[plain.eventId ?: return] = plain
    }

    fun prependEvents(chronological: List<Event>) {
        val merged = LinkedHashMap<String, Event>()
        chronological.forEach { event ->
            event.eventId?.let { if (it !in eventsById) merged[it] = event }
        }
        merged.putAll(eventsById)
        eventsById.clear()
        eventsById.putAll(merged)
    }

    private fun applyRedaction(redactedId: String?) {
        redactedId ?: return
        eventsById.remove(redactedId)
        editsByTarget.remove(redactedId)
        reactionsByTarget.remove(redactedId)
        val removedRelation = relationSourceIndex.remove(redactedId) ?: return
        val (targetId, key) = removedRelation
        if (key == null) {
            if (editsByTarget[targetId]?.eventId == redactedId) {
                editsByTarget.remove(targetId)
            }
        } else {
            reactionsByTarget[targetId]?.get(key)?.removeAll { it.eventId == redactedId }
        }
    }

    private fun buildAnnotations(eventId: String): EventAnnotationsSummary? {
        val edit = editsByTarget[eventId]
        val reactions = reactionsByTarget[eventId]?.mapNotNull { (key, events) ->
            if (events.isEmpty()) return@mapNotNull null
            ReactionAggregatedSummary(
                    key = key,
                    count = events.size,
                    addedByMe = events.any { it.senderId == myUserId },
                    firstTimestamp = events.minOf { it.originServerTs ?: 0 },
                    sourceEvents = events.mapNotNull { it.eventId },
                    localEchoEvents = emptyList(),
            )
        }.orEmpty()
        if (edit == null && reactions.isEmpty()) return null
        return EventAnnotationsSummary(
                reactionsSummary = reactions,
                editSummary = edit?.let {
                    EditAggregatedSummary(
                            latestEdit = it,
                            sourceEvents = listOfNotNull(it.eventId),
                            localEchos = emptyList(),
                            lastEditTs = it.originServerTs ?: 0,
                    )
                },
        )
    }

    /** Rebuild all published snapshots from the mutable structures. Call under [mutex]. */
    fun publish() {
        val displayNameCounts = memberContents.values
                .filter { it.membership == Membership.JOIN }
                .groupingBy { it.displayName }
                .eachCount()
        val chronological = eventsById.values.map { event ->
            val member = event.senderId?.let { memberContents[it] }
            TimelineEvent(
                    root = event,
                    localId = event.eventId.hashCode().toLong(),
                    eventId = event.eventId.orEmpty(),
                    displayIndex = 0,
                    senderInfo = SenderInfo(
                            userId = event.senderId.orEmpty(),
                            displayName = member?.displayName,
                            isUniqueDisplayName = (displayNameCounts[member?.displayName] ?: 1) <= 1,
                            avatarUrl = member?.avatarUrl,
                    ),
                    annotations = event.eventId?.let { buildAnnotations(it) },
            )
        }
        timelineFlow.value = chronological.asReversed().toList()
        stateEventsFlow.value = stateEventsByKey.values.toList()
        membersFlow.value = memberContents.map { (userId, content) ->
            RoomMemberSummary(
                    membership = content.membership,
                    userId = userId,
                    displayName = content.displayName,
                    avatarUrl = content.avatarUrl,
            )
        }
        summaryFlow.value = buildSummary()
    }

    private fun buildSummary(): RoomSummary {
        val canonicalAlias = canonicalAliasContent?.canonicalAlias
        return RoomSummary(
                roomId = roomId,
                displayName = roomName ?: canonicalAlias ?: roomId,
                name = roomName.orEmpty(),
                topic = roomTopic.orEmpty(),
                avatarUrl = roomAvatarUrl.orEmpty(),
                canonicalAlias = canonicalAlias,
                aliases = listOfNotNull(canonicalAlias) + canonicalAliasContent?.alternativeAliases.orEmpty(),
                joinRules = stateEventsByKey[EventType.STATE_ROOM_JOIN_RULES to ""]
                        ?.content?.toModel<RoomJoinRulesContent>()?.joinRules,
                roomType = stateEventsByKey[EventType.STATE_ROOM_CREATE to ""]
                        ?.content?.toModel<RoomCreateContent>()?.type,
                joinedMembersCount = memberContents.values.count { it.membership == Membership.JOIN },
                // Only a small sample is needed (e.g. the member list shows its filter for >1);
                // the full list would bloat every summary diff in big rooms.
                otherMemberIds = memberContents.entries
                        .filter { it.value.membership == Membership.JOIN && it.key != myUserId }
                        .take(8)
                        .map { it.key },
                invitedMembersCount = memberContents.values.count { it.membership == Membership.INVITE },
                membership = Membership.NONE,
                isEncrypted = false,
                encryptionEventTs = null,
                typingUsers = emptyList(),
        )
    }
}
