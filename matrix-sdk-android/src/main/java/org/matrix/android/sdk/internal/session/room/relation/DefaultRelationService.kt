/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.matrix.android.sdk.internal.session.room.relation

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.EventAnnotationsSummary
import org.matrix.android.sdk.api.session.room.model.message.PollType
import org.matrix.android.sdk.api.session.room.model.relation.MassRedactionFloor
import org.matrix.android.sdk.api.session.room.model.relation.MassRedactionRange
import org.matrix.android.sdk.api.session.room.model.relation.PagedEventIds
import org.matrix.android.sdk.api.session.room.model.relation.RelationService
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.util.Cancelable
import org.matrix.android.sdk.api.util.NoOpCancellable
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.room.send.LocalEchoEventFactory
import org.matrix.android.sdk.internal.session.room.send.queue.EventSenderProcessor
import org.matrix.android.sdk.internal.session.room.timeline.GetEventTask
import org.matrix.android.sdk.internal.session.room.timeline.SqlTimelineEventDataSource
import timber.log.Timber

internal class DefaultRelationService @AssistedInject constructor(
        @Assisted private val roomId: String,
        private val eventEditor: EventEditor,
        private val eventSenderProcessor: EventSenderProcessor,
        private val eventFactory: LocalEchoEventFactory,
        private val findReactionEventForUndoTask: FindReactionEventForUndoTask,
        private val fetchEditHistoryTask: FetchEditHistoryTask,
        private val fetchReactionsTask: FetchReactionsTask,
        private val fetchUserEventsTask: FetchUserEventsTask,
        private val getEventTask: GetEventTask,
        private val redactEventTask: org.matrix.android.sdk.internal.crypto.tasks.RedactEventTask,
        private val redactionEventProcessor: org.matrix.android.sdk.internal.session.room.prune.RedactionEventProcessor,
        @org.matrix.android.sdk.internal.di.UserId private val userId: String,
        private val localEchoRepository: org.matrix.android.sdk.internal.session.room.send.LocalEchoRepository,
        private val timelineEventDataSource: SqlTimelineEventDataSource,
        private val roomAPI: org.matrix.android.sdk.internal.session.room.RoomAPI,
        private val globalErrorReceiver: org.matrix.android.sdk.internal.network.GlobalErrorReceiver,
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
) : RelationService {

    @AssistedFactory
    interface Factory {
        fun create(roomId: String): DefaultRelationService
    }

    override fun sendReaction(targetEventId: String, reaction: String): Cancelable {
        val targetTimelineEvent = timelineEventDataSource.getTimelineEvent(roomId, targetEventId)
        return if (targetTimelineEvent
                        ?.annotations
                        ?.reactionsSummary
                        .orEmpty()
                        .none { it.addedByMe && it.key == reaction }) {
            val event = eventFactory.createReactionEvent(roomId, targetTimelineEvent?.eventId ?: targetEventId, reaction)
                    .also { saveLocalEcho(it) }
            eventSenderProcessor.postEvent(event, false /* reaction are not encrypted*/)
        } else {
            Timber.w("Reaction already added")
            NoOpCancellable
        }
    }

    override suspend fun undoReaction(targetEventId: String, reaction: String): Cancelable {
        val params = FindReactionEventForUndoTask.Params(
                roomId,
                targetEventId,
                reaction
        )

        val data = findReactionEventForUndoTask.executeRetry(params, Int.MAX_VALUE)

        return if (data.redactEventId == null) {
            Timber.w("Cannot find reaction to undo (not yet synced?)")
            // TODO?
            NoOpCancellable
        } else {
            val redactEvent = eventFactory.createRedactEvent(roomId, data.redactEventId, null)
                    .also { saveLocalEcho(it) }
            eventSenderProcessor.postRedaction(redactEvent, null)
        }
    }

    override fun editPoll(
            targetEvent: TimelineEvent,
            pollType: PollType,
            question: String,
            options: List<String>
    ): Cancelable {
        return eventEditor.editPoll(targetEvent, pollType, question, options)
    }

    override fun editMediaContent(targetEvent: TimelineEvent, newContent: Content): Cancelable {
        return eventEditor.editMediaContent(targetEvent, newContent)
    }

    override fun editMediaCaption(
            targetEvent: TimelineEvent,
            newCaption: CharSequence,
            newFormattedCaption: String?,
    ): Cancelable {
        return eventEditor.editMediaCaption(targetEvent, newCaption, newFormattedCaption)
    }

    override fun editTextMessage(
            targetEvent: TimelineEvent,
            msgType: String,
            newBodyText: CharSequence,
            newFormattedBodyText: CharSequence?,
            newBodyAutoMarkdown: Boolean,
            compatibilityBodyText: String
    ): Cancelable {
        return eventEditor.editTextMessage(targetEvent, msgType, newBodyText, newFormattedBodyText, newBodyAutoMarkdown, compatibilityBodyText)
    }

    override fun editReply(
            replyToEdit: TimelineEvent,
            originalTimelineEvent: TimelineEvent,
            newBodyText: CharSequence,
            newFormattedBodyText: String?,
            compatibilityBodyText: String
    ): Cancelable {
        return eventEditor.editReply(replyToEdit, originalTimelineEvent, newBodyText, newFormattedBodyText, compatibilityBodyText)
    }

    override suspend fun fetchEditHistory(eventId: String): List<Event> {
        return fetchEditHistoryTask.execute(FetchEditHistoryTask.Params(roomId, eventId))
    }

    override suspend fun fetchReactions(eventId: String): List<Event> {
        return fetchReactionsTask.execute(FetchReactionsTask.Params(roomId, eventId))
    }

    override suspend fun clearSendingRedactions() {
        // Remove any stuck local-echo redactions (legacy of the echo-based path) so they stop showing as
        // "sending" forever. The no-echo path doesn't create these.
        val stuck = stores.timelineEvent.getSendingByRoom(roomId)
                .filter { it.root?.type == EventType.REDACTION }
        Timber.i("massredact: clearing ${stuck.size} stuck sending redactions in $roomId")
        stuck.forEach { localEchoRepository.deleteFailedEcho(roomId, it.eventId) }
    }

    override suspend fun redactEventNoEcho(eventId: String, reason: String?) {
        val redactionEventId = redactEventTask.execute(
                org.matrix.android.sdk.internal.crypto.tasks.RedactEventTask.Params(
                        txID = java.util.UUID.randomUUID().toString(),
                        roomId = roomId,
                        eventId = eventId,
                        reason = reason,
                        withRelTypes = null,
                )
        )
        // A bulk redaction makes sync gappy (limited), so most of these redactions never come back
        // through /sync to prune their targets — apply each to the local copy right away, or a re-run
        // would enumerate every already-redacted event again.
        database.awaitDbTransaction(dispatcher) {
            redactionEventProcessor.prune(stores, Event(
                    type = EventType.REDACTION,
                    eventId = redactionEventId,
                    redacts = eventId,
                    roomId = roomId,
                    senderId = userId,
                    originServerTs = System.currentTimeMillis(),
            ))
        }
    }

    override fun getLocalEventIdsFromUser(userId: String, range: MassRedactionRange): List<String> {
        return stores.event.getRedactableEventIdsBySender(roomId, userId, range)
    }

    override suspend fun fetchMoreEventIdsFromUser(
            userId: String,
            fromToken: String?,
            floor: MassRedactionFloor?,
            range: MassRedactionRange,
    ): PagedEventIds {
        val result = fetchUserEventsTask.execute(FetchUserEventsTask.Params(roomId, userId, fromToken, floor?.ts, floor?.anchorToken, range))
        return PagedEventIds(result.eventIds, result.nextToken, result.redactionTargets, result.alreadyRedactedIds)
    }

    override suspend fun markRedactedLocally(eventIds: List<String>) {
        // Reconcile stale local rows with server truth: a row the server serves as redacted but the local
        // DB still has unmarked (its redaction never synced) would otherwise be re-redacted by the next
        // local sweep. Prune it now exactly as if the redaction had arrived.
        val stale = eventIds.filter {
            val dbId = stores.event.getDbId(roomId, it) ?: return@filter false
            stores.event.getById(dbId)?.unsignedData?.contains("redacted_b") != true
        }
        if (stale.isEmpty()) return
        database.awaitDbTransaction(dispatcher) {
            stale.forEach { eventId ->
                redactionEventProcessor.prune(stores, Event(
                        type = EventType.REDACTION,
                        redacts = eventId,
                        roomId = roomId,
                ))
            }
        }
    }

    override fun getKnownRedactionTargets(): Set<String> = stores.event.getRedactionTargets(roomId)

    // A user can't have sent anything before their earliest self-sent membership event (join/knock).
    // Walk the m.room.member replaces_state chain to the start; only return a floor when the walk
    // resolves fully — otherwise null (page in full) so we never stop early and miss events.
    override suspend fun getMassRedactionFloor(userId: String, notBeforeTs: Long?): MassRedactionFloor? {
        var eventId = stores.currentStateEvent.getOne(roomId, EventType.STATE_ROOM_MEMBER, userId)?.eventId ?: return null
        var earliestSelfTs: Long? = null
        var reachedChainStart = false
        // Guard against a malformed replaces_state cycle, not chain length — the walk must visit every
        // membership change (rejoins, name changes) however many there are.
        val visited = HashSet<String>()
        while (visited.add(eventId)) {
            val event = tryOrNull { getEventTask.execute(GetEventTask.Params(roomId, eventId)) } ?: return null
            if (event.senderId == userId && event.originServerTs != null) {
                earliestSelfTs = event.originServerTs
            }
            val prev = event.unsignedData?.replacesState
            if (prev == null) {
                reachedChainStart = true
                break
            }
            eventId = prev
        }
        if (!reachedChainStart) return null
        val ts = maxOf(earliestSelfTs ?: return null, notBeforeTs ?: Long.MIN_VALUE)
        // A pagination token at the floor lets the walk run FORWARDS from the user's join to the live
        // edge — a forward walk can never touch (or backfill over federation) pre-join history, which is
        // what made backwards pages hang on sparsely-cached rooms. Note the token's topological part may
        // be the placeholder depth (2^53-2, e.g. on rooms where every event is stored that way); the
        // stream part still positions it correctly for forward paging.
        val anchorToken = tryOrNull {
            val anchor = executeRequest(globalErrorReceiver) { roomAPI.getEventForTimestamp(roomId, ts, "f") }
            executeRequest(globalErrorReceiver) { roomAPI.getContextOfEvent(roomId, anchor.eventId, 0) }.start
        }
        return MassRedactionFloor(ts, anchorToken)
    }

    override fun replyToMessage(
            eventReplied: TimelineEvent,
            replyText: CharSequence,
            replyFormattedText: CharSequence?,
            autoMarkdown: Boolean,
            showInThread: Boolean,
            rootThreadEventId: String?,
            msgType: String,
    ): Cancelable? {
        val event = eventFactory.createReplyTextEvent(
                roomId = roomId,
                eventReplied = eventReplied,
                replyText = replyText,
                replyTextFormatted = replyFormattedText,
                autoMarkdown = autoMarkdown,
                rootThreadEventId = rootThreadEventId,
                showInThread = showInThread,
                msgType = msgType,
        )
                ?.also { saveLocalEcho(it) }
                ?: return null

        return eventSenderProcessor.postEvent(event)
    }

    override fun getEventAnnotationsSummary(eventId: String): EventAnnotationsSummary? {
        return stores.annotations.get(eventId)?.asDomain()
    }

    override fun getEventAnnotationsSummaryFlow(eventId: String): Flow<Optional<EventAnnotationsSummary>> {
        // Reactions are the dominant live-updating annotation; observe them and re-resolve the full summary.
        return database.eventAnnotationsSummaryQueries.selectReactions(eventId).asFlow().mapToList(dispatcher)
                .map { stores.annotations.get(eventId)?.asDomain().toOptional() }
    }

    override fun replyInThread(
            rootThreadEventId: String,
            replyInThreadText: CharSequence,
            msgType: String,
            autoMarkdown: Boolean,
            formattedText: String?,
            eventReplied: TimelineEvent?
    ): Cancelable? {
        val event = if (eventReplied != null) {
            // Reply within a thread
            eventFactory.createReplyTextEvent(
                    roomId = roomId,
                    eventReplied = eventReplied,
                    replyText = replyInThreadText,
                    replyTextFormatted = formattedText,
                    autoMarkdown = autoMarkdown,
                    rootThreadEventId = rootThreadEventId,
                    showInThread = false
            )
                    ?.also {
                        saveLocalEcho(it)
                    }
                    ?: return null
        } else {
            // Normal thread reply
            eventFactory.createThreadTextEvent(
                    rootThreadEventId = rootThreadEventId,
                    roomId = roomId,
                    text = replyInThreadText,
                    msgType = msgType,
                    autoMarkdown = autoMarkdown,
                    formattedText = formattedText
            )
                    .also {
                        saveLocalEcho(it)
                    }
        }
        return eventSenderProcessor.postEvent(event)
    }

    /**
     * Saves the event in database as a local echo.
     * SendState is set to UNSENT and it's added to a the sendingTimelineEvents list of the room.
     * The sendingTimelineEvents is checked on new sync and will remove the local echo if an event with
     * the same transaction id is received (in unsigned data)
     */
    private fun saveLocalEcho(event: Event) {
        eventFactory.createLocalEcho(event)
    }
}
