/*
 * Copyright (c) 2021 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.EventAnnotationsSummary
import org.matrix.android.sdk.api.session.room.model.ReactionAggregatedSummary
import org.matrix.android.sdk.api.session.room.model.relation.ReactionContent
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber
import java.util.Collections

internal class UIEchoManager(
        private val listener: Listener,
        private val clock: Clock,
) {

    interface Listener {
        fun rebuildEvent(eventId: String, builder: (TimelineEvent) -> TimelineEvent?): Boolean
    }

    private val inMemorySendingEvents = Collections.synchronizedList<TimelineEvent>(ArrayList())

    // synchronizedList only guards single ops; iterating (find/removeAll/toList) needs manual locking on
    // the list, or a concurrent add (e.g. the redaction burst on another thread) throws ConcurrentModificationException.
    fun getInMemorySendingEvents(): List<TimelineEvent> {
        return synchronized(inMemorySendingEvents) { ArrayList(inMemorySendingEvents) }
    }

    /**
     * Due to lag of DB updates, we keep some UI echo of some properties to update timeline faster.
     */
    private val inMemorySendingStates = Collections.synchronizedMap<String, SendState>(HashMap())

    private val inMemoryReactions = Collections.synchronizedMap<String, MutableList<ReactionUiEchoData>>(HashMap())

    fun onSentEventsInDatabase(eventIds: List<String>) {
        // Remove in memory as soon as they are known by database
        synchronized(inMemorySendingEvents) {
            eventIds.forEach { eventId ->
                inMemorySendingEvents.removeAll { eventId == it.eventId }
            }
        }
        // Reaction echoes are deliberately not dropped here. Leaving the sending table means the reaction
        // was stored, not that its aggregation reached the timeline, so
        // [decorateEventWithReactionUiEcho] prunes them once the summary confirms them.
    }

    fun hasPendingReactionEchoes(): Boolean =
            synchronized(inMemoryReactions) { inMemoryReactions.values.any { it.isNotEmpty() } }

    /**
     * The reacted-on message just traded its local echo id for its server id, and the reaction echoes are
     * filed under the old one. They are needed for a moment longer, because the aggregation that files the
     * reaction under the new id runs in a later transaction than the sync that inserted the row.
     */
    fun onEchoResolved(localEchoId: String, remoteEventId: String) {
        if (localEchoId == remoteEventId) return
        synchronized(inMemoryReactions) {
            val pending = inMemoryReactions.remove(localEchoId) ?: return
            val moved = pending.map { it.copy(reactedOnEventId = remoteEventId) }
            inMemoryReactions.getOrPut(remoteEventId) { mutableListOf() }
                    .let { existing -> moved.forEach { m -> if (existing.none { e -> e.localEchoId == m.localEchoId }) existing.add(m) } }
        }
    }

    fun onSendStateUpdated(eventId: String, sendState: SendState): Boolean {
        val existingState = inMemorySendingStates[eventId]
        inMemorySendingStates[eventId] = sendState
        return existingState != sendState
    }

    fun onLocalEchoCreated(timelineEvent: TimelineEvent): Boolean {
        when (timelineEvent.root.getClearType()) {
            EventType.REDACTION -> {
            }
            EventType.REACTION -> {
                val content: ReactionContent? = timelineEvent.root.content?.toModel<ReactionContent>()
                if (RelationType.ANNOTATION == content?.relatesTo?.type) {
                    val reaction = content.relatesTo.key
                    val relatedEventID = content.relatesTo.eventId
                    inMemoryReactions.getOrPut(relatedEventID) { mutableListOf() }
                            .add(
                                    ReactionUiEchoData(
                                            localEchoId = timelineEvent.eventId,
                                            reactedOnEventId = relatedEventID,
                                            reaction = reaction
                                    )
                            )
                    listener.rebuildEvent(relatedEventID) {
                        decorateEventWithReactionUiEcho(it)
                    }
                }
            }
        }
        Timber.v("On local echo created: ${timelineEvent.eventId}")
        inMemorySendingEvents.add(0, timelineEvent)
        return true
    }

    fun decorateEventWithReactionUiEcho(timelineEvent: TimelineEvent): TimelineEvent {
        val relatedEventID = timelineEvent.eventId
        val contents = inMemoryReactions[relatedEventID] ?: return timelineEvent

        var existingAnnotationSummary = timelineEvent.annotations ?: EventAnnotationsSummary()
        val updateReactions = existingAnnotationSummary.reactionsSummary.toMutableList()

        val confirmed = mutableListOf<ReactionUiEchoData>()
        contents.forEach { uiEchoReaction ->
            val indexOfExistingReaction = updateReactions.indexOfFirst { it.key == uiEchoReaction.reaction }
            val existingEntry = updateReactions.getOrNull(indexOfExistingReaction)
            if (existingEntry != null && existingEntry.addedByMe && uiEchoReaction.localEchoId !in existingEntry.localEchoEvents) {
                // Counted under its synced id now, so merging the echo again would double it.
                confirmed.add(uiEchoReaction)
                return@forEach
            }
            if (indexOfExistingReaction == -1) {
                // just add the new key
                ReactionAggregatedSummary(
                        key = uiEchoReaction.reaction,
                        count = 1,
                        addedByMe = true,
                        firstTimestamp = clock.epochMillis(),
                        sourceEvents = emptyList(),
                        localEchoEvents = listOf(uiEchoReaction.localEchoId)
                ).let { updateReactions.add(it) }
            } else {
                // update Existing Key
                val existing = updateReactions[indexOfExistingReaction]
                if (!existing.localEchoEvents.contains(uiEchoReaction.localEchoId)) {
                    updateReactions.remove(existing)
                    // only update if echo is not yet there
                    ReactionAggregatedSummary(
                            key = existing.key,
                            count = existing.count + 1,
                            addedByMe = true,
                            firstTimestamp = existing.firstTimestamp,
                            sourceEvents = existing.sourceEvents,
                            localEchoEvents = existing.localEchoEvents + uiEchoReaction.localEchoId

                    ).let { updateReactions.add(indexOfExistingReaction, it) }
                }
            }
        }

        if (confirmed.isNotEmpty()) {
            synchronized(inMemoryReactions) { inMemoryReactions[relatedEventID]?.removeAll(confirmed) }
        }
        existingAnnotationSummary = existingAnnotationSummary.copy(
                reactionsSummary = updateReactions
        )
        return timelineEvent.copy(
                annotations = existingAnnotationSummary
        )
    }

    fun updateSentStateWithUiEcho(timelineEvent: TimelineEvent): TimelineEvent {
        if (timelineEvent.root.sendState.isSent()) return timelineEvent
        val inMemoryState = inMemorySendingStates[timelineEvent.eventId] ?: return timelineEvent
        if (timelineEvent.root.sendState == inMemoryState) return timelineEvent
        // Timber.v("## ${clock.epochMillis()} Send event refresh echo with live state $inMemoryState from state ${element.root.sendState}")
        return timelineEvent.copy(
                root = timelineEvent.root.copyAll()
                        .also { it.sendState = inMemoryState }
        )
    }

    /**
     * [dropReactionEcho] only for an echo that will never sync (send failed, or cancelled). A reaction
     * event reaching the chunk is not confirmation, since its aggregation may not be mapped into the
     * target yet, so that case is left to [decorateEventWithReactionUiEcho].
     */
    fun onSyncedEvent(transactionId: String?, dropReactionEcho: Boolean = false) {
        synchronized(inMemorySendingEvents) {
            val sendingEvent = inMemorySendingEvents.find {
                it.eventId == transactionId
            }
            inMemorySendingEvents.remove(sendingEvent)
        }
        if (dropReactionEcho) {
            synchronized(inMemoryReactions) {
                inMemoryReactions.forEach { (_, u) -> u.removeAll { it.localEchoId == transactionId } }
            }
        }
        inMemorySendingStates.remove(transactionId)
    }
}
