/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.room

import org.matrix.android.sdk.api.session.crypto.verification.VerificationState
import org.matrix.android.sdk.api.session.events.model.AggregatedAnnotation
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.content.EncryptedEventContent
import org.matrix.android.sdk.api.session.events.model.getRelationContent
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.ReferencesAggregatedContent
import org.matrix.android.sdk.api.session.room.model.message.MessageBeaconInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageBeaconLocationDataContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessagePollContent
import org.matrix.android.sdk.api.session.room.model.message.MessagePollResponseContent
import org.matrix.android.sdk.api.session.room.model.message.MessageRelationContent
import org.matrix.android.sdk.api.session.room.model.relation.ReactionContent
import org.matrix.android.sdk.internal.SessionManager
import org.matrix.android.sdk.internal.crypto.verification.toState
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.model.EditAggregatedSummaryEntity
import org.matrix.android.sdk.internal.database.model.EditionOfEvent
import org.matrix.android.sdk.internal.database.model.EventAnnotationsSummaryEntity
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.EventInsertType
import org.matrix.android.sdk.internal.database.model.ReactionAggregatedSummaryEntity
import org.matrix.android.sdk.internal.database.model.ReferencesAggregatedSummaryEntity
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.SessionId
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.EventInsertLiveProcessor
import org.matrix.android.sdk.internal.session.room.aggregation.livelocation.LiveLocationAggregationProcessor
import org.matrix.android.sdk.internal.session.room.aggregation.poll.PollAggregationProcessor
import org.matrix.android.sdk.internal.session.room.aggregation.utd.EncryptedReferenceAggregationProcessor
import org.matrix.android.sdk.internal.session.room.powerlevels.getRoomPowerLevels
import org.matrix.android.sdk.internal.session.room.state.StateEventDataSource
import org.matrix.android.sdk.internal.session.room.summary.RoomSummaryPreviewInvalidation
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber
import javax.inject.Inject

/**
 * Aggregates reactions, edits, references (SAS verification display) and — once their sub-processors
 * are ported — polls and live-location summaries.
 *
 * Each handler reads the (unmanaged) [EventAnnotationsSummaryEntity] from the SQL store, mutates its
 * in-memory aggregation lists, then writes them back wholesale via replaceReactions/replaceEditions/
 * upsertReferences. [SessionStores.annotations] owns the child rows; the parent summary row is ensured
 * with upsertSummary (the Realm getOrCreate equivalent).
 */
internal class EventRelationsAggregationProcessor @Inject constructor(
        @UserId private val userId: String,
        @SessionId private val sessionId: String,
        private val editValidator: EventEditValidator,
        private val clock: Clock,
        private val sessionManager: SessionManager,
        private val pollAggregationProcessor: PollAggregationProcessor,
        private val liveLocationAggregationProcessor: LiveLocationAggregationProcessor,
        private val encryptedReferenceAggregationProcessor: EncryptedReferenceAggregationProcessor,
        private val stateEventDataSource: StateEventDataSource,
        private val previewInvalidation: RoomSummaryPreviewInvalidation,
) : EventInsertLiveProcessor {

    // OPT OUT server aggregation until API mature enough (should be true to work with e2e)
    private val SHOULD_HANDLE_SERVER_AGREGGATION = false

    private val allowedTypes = listOf(
            EventType.MESSAGE,
            EventType.REDACTION,
            EventType.REACTION,
            // The aggregator handles verification events but just to render tiles in the timeline
            // It's not participating in verification itself, just timeline display
            EventType.KEY_VERIFICATION_DONE,
            EventType.KEY_VERIFICATION_CANCEL,
            EventType.KEY_VERIFICATION_ACCEPT,
            EventType.KEY_VERIFICATION_START,
            EventType.KEY_VERIFICATION_MAC,
            EventType.KEY_VERIFICATION_READY,
            EventType.KEY_VERIFICATION_KEY,
            EventType.ENCRYPTED
    ) +
            EventType.POLL_START.values +
            EventType.POLL_RESPONSE.values +
            EventType.POLL_END.values +
            EventType.STATE_ROOM_BEACON_INFO.values +
            EventType.BEACON_LOCATION_DATA.values

    override fun shouldProcess(eventId: String, eventType: String, insertType: EventInsertType): Boolean {
        return allowedTypes.contains(eventType)
    }

    override fun process(stores: SessionStores, event: Event) {
        try {
            val roomId = event.roomId
            if (roomId == null) {
                Timber.w("Event has no room id ${event.eventId}")
                return
            }
            val isLocalEcho = LocalEcho.isLocalEchoId(event.eventId ?: "")

            // It might be a late decryption of the original event (or a back-paginated one): clean any
            // edition that is now revealed as invalid against this freshly known event.
            if (!isLocalEcho) {
                cleanInvalidEditions(stores, event)
            }

            when (event.getClearType()) {
                EventType.REACTION -> {
                    Timber.v("###REACTION in room $roomId , reaction eventID ${event.eventId}")
                    handleReaction(stores, event, roomId, isLocalEcho)
                }
                EventType.ENCRYPTED -> {
                    val encryptedEventContent = event.content.toModel<EncryptedEventContent>()
                    processEncryptedContent(encryptedEventContent, stores, event, roomId, isLocalEcho)
                }
                EventType.MESSAGE -> {
                    if (event.unsignedData?.relations?.annotations != null && SHOULD_HANDLE_SERVER_AGREGGATION) {
                        handleInitialAggregatedRelations(stores, event, roomId, event.unsignedData.relations.annotations)
                    }
                    val relationContent = event.getRelationContent()
                    if (relationContent?.type == RelationType.REPLACE) {
                        Timber.v("###REPLACE in room $roomId for event ${event.eventId}")
                        handleReplace(stores, event, roomId, isLocalEcho, relationContent.eventId)
                    }
                }
                EventType.KEY_VERIFICATION_DONE,
                EventType.KEY_VERIFICATION_CANCEL,
                EventType.KEY_VERIFICATION_ACCEPT,
                EventType.KEY_VERIFICATION_START,
                EventType.KEY_VERIFICATION_MAC,
                EventType.KEY_VERIFICATION_READY,
                EventType.KEY_VERIFICATION_KEY -> {
                    Timber.v("## SAS REF in room $roomId for event ${event.eventId}")
                    event.content.toModel<MessageRelationContent>()?.relatesTo?.let {
                        if (it.type == RelationType.REFERENCE && it.eventId != null) {
                            handleVerification(stores, event, roomId, isLocalEcho, it.eventId)
                        }
                    }
                }
                EventType.REDACTION -> {
                    val eventToPrune = event.redacts?.let { stores.event.getByEventId(it) } ?: return
                    when (eventToPrune.type) {
                        EventType.MESSAGE -> {
                            Timber.d("REDACTION for message ${eventToPrune.eventId}")
                            val contentModel = ContentMapper.map(eventToPrune.content)?.toModel<MessageContent>()
                            if (RelationType.REPLACE == contentModel?.relatesTo?.type && contentModel.relatesTo?.eventId != null) {
                                handleRedactionOfReplace(stores, eventToPrune.eventId, contentModel.relatesTo!!.eventId!!)
                            }
                        }
                        EventType.REACTION -> {
                            handleReactionRedact(stores, eventToPrune)
                        }
                    }
                }
                in EventType.POLL_START.values -> {
                    val content: MessagePollContent? = event.content.toModel()
                    if (content?.relatesTo?.type == RelationType.REPLACE) {
                        Timber.v("###REPLACE poll in room $roomId for event ${event.eventId}")
                        handleReplace(stores, event, roomId, isLocalEcho, content.relatesTo.eventId)
                    }
                }
                in EventType.POLL_RESPONSE.values -> {
                    event.content.toModel<MessagePollResponseContent>(catchError = true)?.let {
                        sessionManager.getSessionComponent(sessionId)?.session()?.let { session ->
                            pollAggregationProcessor.handlePollResponseEvent(session, stores, event)
                        }
                    }
                }
                in EventType.POLL_END.values -> {
                    sessionManager.getSessionComponent(sessionId)?.session()?.let { session ->
                        val roomPowerLevels = stateEventDataSource.getRoomPowerLevels(roomId)
                        pollAggregationProcessor.handlePollEndEvent(session, roomPowerLevels, stores, event)
                    }
                }
                in EventType.STATE_ROOM_BEACON_INFO.values -> {
                    event.content.toModel<MessageBeaconInfoContent>(catchError = true)?.let {
                        liveLocationAggregationProcessor.handleBeaconInfo(stores, event, it, roomId, isLocalEcho)
                    }
                }
                in EventType.BEACON_LOCATION_DATA.values -> {
                    event.getClearContent().toModel<MessageBeaconLocationDataContent>(catchError = true)?.let {
                        liveLocationAggregationProcessor.handleBeaconLocationData(
                                stores = stores,
                                event = event,
                                content = it,
                                roomId = roomId,
                                relatedEventId = event.getRelationContent()?.eventId,
                                isLocalEcho = isLocalEcho,
                        )
                    }
                }
                else -> Timber.v("UnHandled event ${event.eventId}")
            }
        } catch (t: Throwable) {
            Timber.e(t, "## Should not happen ")
        }
    }

    private fun processEncryptedContent(
            encryptedEventContent: EncryptedEventContent?,
            stores: SessionStores,
            event: Event,
            roomId: String,
            isLocalEcho: Boolean,
    ) {
        when (encryptedEventContent?.relatesTo?.type) {
            RelationType.REPLACE -> {
                Timber.w("## UTD replace in room $roomId for event ${event.eventId}")
            }
            RelationType.RESPONSE -> {
                Timber.w("## UTD response in room $roomId related to ${encryptedEventContent.relatesTo.eventId}")
            }
            RelationType.REFERENCE -> {
                Timber.w("## UTD reference in room $roomId related to ${encryptedEventContent.relatesTo.eventId}")
                encryptedReferenceAggregationProcessor.handle(
                        stores = stores,
                        event = event,
                        isLocalEcho = isLocalEcho,
                        relatedEventId = encryptedEventContent.relatesTo.eventId,
                )
            }
            RelationType.ANNOTATION -> {
                Timber.w("## UTD annotation in room $roomId related to ${encryptedEventContent.relatesTo.eventId}")
            }
            else -> Unit
        }
    }

    private fun cleanInvalidEditions(stores: SessionStores, event: Event) {
        val targetEventId = event.eventId ?: return
        val summary = stores.annotations.get(targetEventId) ?: return
        val editSummary = summary.editSummary ?: return
        val kept = editSummary.editions.filter { editionOfEvent ->
            val editEvent = stores.event.getByEventId(editionOfEvent.eventId)?.asDomain()
            // keep unknown edit events (might be validated later); drop the ones now shown invalid
            editEvent == null || editValidator.validateEdit(event, editEvent) !is EventEditValidator.EditValidity.Invalid
        }
        if (kept.size != editSummary.editions.size) {
            Timber.v("## Replace: dropping ${editSummary.editions.size - kept.size} invalid edition(s) for $targetEventId")
            editSummary.editions = ArrayList<EditionOfEvent>().apply { addAll(kept) }
            // Touch event_annotations_summary so the timeline's annotation-change flow fires (it doesn't watch
            // the editions table) — see handleReactionRedact.
            stores.annotations.upsertSummary(targetEventId, summary.roomId)
            stores.annotations.replaceEditions(targetEventId, editSummary)
        }
    }

    private fun handleReplace(
            stores: SessionStores,
            event: Event,
            roomId: String,
            isLocalEcho: Boolean,
            relatedEventId: String?
    ) {
        val eventId = event.eventId ?: return
        val targetEventId = relatedEventId ?: return
        val editedEvent = stores.event.getByEventId(targetEventId)

        when (val validity = editValidator.validateEdit(editedEvent?.asDomain(), event)) {
            is EventEditValidator.EditValidity.Invalid -> {
                Timber.w("Dropping invalid edit ${event.eventId}, reason:${validity.reason}")
                return
            }
            // we can't drop the source event might be unknown, will be validated later
            EventEditValidator.EditValidity.Unknown,
            EventEditValidator.EditValidity.Valid -> Unit
        }

        val eventSummary = stores.annotations.get(targetEventId)
                ?: EventAnnotationsSummaryEntity(eventId = targetEventId, roomId = roomId)

        val existingSummary = eventSummary.editSummary
        if (existingSummary == null) {
            Timber.v("###REPLACE new edit summary for $targetEventId, creating one (localEcho:$isLocalEcho)")
            eventSummary.editSummary = EditAggregatedSummaryEntity(
                    editions = ArrayList<EditionOfEvent>().apply {
                        add(newEdition(eventId, event.originServerTs, isLocalEcho))
                    }
            )
        } else {
            if (existingSummary.editions.any { it.eventId == eventId }) {
                Timber.v("###REPLACE ignoring event for summary, it's known $eventId")
                return
            }
            val txId = event.unsignedData?.transactionId
            // is it a remote echo of one of our local echoes?
            if (!isLocalEcho && existingSummary.editions.any { it.eventId == txId }) {
                Timber.v("###REPLACE Receiving remote echo of edit (edit already done)")
                existingSummary.editions.firstOrNull { it.eventId == txId }?.let {
                    it.eventId = eventId
                    it.timestamp = event.originServerTs ?: clock.epochMillis()
                    it.isLocalEcho = false
                }
            } else {
                Timber.v("###REPLACE Computing aggregated edit summary (isLocalEcho:$isLocalEcho)")
                existingSummary.editions.add(newEdition(eventId, event.originServerTs, isLocalEcho))
            }
        }

        if (event.getClearType() in EventType.POLL_START.values) {
            pollAggregationProcessor.handlePollStartEvent(stores, event)
        }

        // Thread-summary edition display is applied at serving time (ThreadsService.enhanceThreadWithEditions
        // reads this edit summary), so no thread-root pointer fix-up is needed here.
        stores.annotations.upsertSummary(targetEventId, roomId)
        stores.annotations.replaceEditions(targetEventId, eventSummary.editSummary)

        // Aggregation runs async, after the sync already refreshed the room summary, and an edit leaves
        // the preview's row untouched — so the room list keeps its (row-keyed) memoized mapping of the
        // pre-edit text. Evict that mapping and touch the row to force a re-emit, exactly as decryption
        // does for the same reason.
        stores.roomSummary.roomIdsWithPreviewEvent(listOf(targetEventId)).forEach { previewRoomId ->
            previewInvalidation.onPreviewChanged(previewRoomId)
            stores.roomSummary.touch(previewRoomId)
        }
    }

    // MSC2675: seed reaction counts from the server's bundled aggregations (used for E2E rooms where the
    // individual reaction events can't be aggregated client-side). Only seeds when this event isn't tracked yet.
    private fun handleInitialAggregatedRelations(stores: SessionStores, event: Event, roomId: String, aggregation: AggregatedAnnotation) {
        val eventId = event.eventId ?: return
        if (stores.annotations.get(eventId) != null) return
        val reactions = aggregation.chunk.orEmpty()
                .filter { it.type == EventType.REACTION }
                .map { ReactionAggregatedSummaryEntity(key = it.key, count = it.count, firstTimestamp = event.originServerTs ?: 0) }
        if (reactions.isNotEmpty()) {
            stores.annotations.upsertSummary(eventId, roomId)
            stores.annotations.replaceReactions(eventId, reactions)
        }
    }

    private fun newEdition(eventId: String, originServerTs: Long?, isLocalEcho: Boolean) = EditionOfEvent(
            eventId = eventId,
            // Do not take local echo originServerTs here, could mess up ordering (keep old ts)
            timestamp = if (isLocalEcho) clock.epochMillis() else originServerTs ?: clock.epochMillis(),
            isLocalEcho = isLocalEcho,
    )

    private fun handleReaction(
            stores: SessionStores,
            event: Event,
            roomId: String,
            isLocalEcho: Boolean
    ) {
        val content = event.content.toModel<ReactionContent>()
        if (content == null) {
            Timber.e("Malformed reaction content ${event.content}")
            return
        }
        // rel_type must be m.annotation
        if (RelationType.ANNOTATION != content.relatesTo?.type) {
            Timber.e("Unknown relation type ${content.relatesTo?.type} for event ${event.eventId}")
            return
        }
        val reaction = content.relatesTo.key
        val relatedEventID = content.relatesTo.eventId
        val reactionEventId = event.eventId
        Timber.v("Reaction $reactionEventId relates to $relatedEventID")
        val eventSummary = stores.annotations.get(relatedEventID)
                ?: EventAnnotationsSummaryEntity(eventId = relatedEventID, roomId = roomId)

        val sum = eventSummary.reactionsSummary.find { it.key == reaction }
                ?: ReactionAggregatedSummaryEntity(key = reaction, firstTimestamp = event.originServerTs ?: 0).also {
                    eventSummary.reactionsSummary.add(it)
                }
        val txId = event.unsignedData?.transactionId
        if (isLocalEcho && txId.isNullOrBlank()) {
            Timber.w("Received a local echo with no transaction ID")
        }

        if (isLocalEcho) {
            if (txId != null && !sum.sourceLocalEcho.contains(txId)) {
                Timber.v("Adding local echo reaction")
                sum.sourceLocalEcho.add(txId)
            }
        } else {
            if (reactionEventId != null && !sum.sourceEvents.contains(reactionEventId)) {
                Timber.v("Adding synced reaction")
                sum.sourceEvents.add(reactionEventId)
            }
            // Drop the pending local echo this synced reaction reconciles with. Prefer the transaction id,
            // but some homeservers omit unsigned.transaction_id, so fall back to any pending echo of our own
            // (a user can only react once per key).
            val matchingLocalEcho = when {
                txId != null && sum.sourceLocalEcho.contains(txId) -> txId
                event.senderId == userId -> sum.sourceLocalEcho.firstOrNull()
                else -> null
            }
            if (matchingLocalEcho != null) {
                Timber.v("Reconciling local echo for reaction")
                sum.sourceLocalEcho.remove(matchingLocalEcho)
            }
        }

        refreshReactionSummary(stores, sum)
        stores.annotations.upsertSummary(relatedEventID, roomId)
        stores.annotations.replaceReactions(relatedEventID, eventSummary.reactionsSummary)
    }

    /**
     * Derive [count] and [addedByMe] from the source lists so they can never drift out of sync with
     * the reactions actually known (which is what causes inflated counters and stale highlight state).
     */
    private fun refreshReactionSummary(stores: SessionStores, sum: ReactionAggregatedSummaryEntity) {
        sum.count = sum.sourceEvents.size + sum.sourceLocalEcho.size
        // Local echoes are always our own; otherwise look up the sender of each known source event.
        sum.addedByMe = sum.sourceLocalEcho.isNotEmpty() ||
                sum.sourceEvents.any { stores.event.getByEventId(it)?.sender == userId }
    }

    private fun handleRedactionOfReplace(
            stores: SessionStores,
            redactedEventId: String,
            relatedEventId: String
    ) {
        Timber.d("Handle redaction of m.replace")
        val eventSummary = stores.annotations.get(relatedEventId)
        if (eventSummary == null) {
            Timber.w("Redaction of a replace targeting an unknown event $relatedEventId")
            return
        }
        val editSummary = eventSummary.editSummary
        val sourceToDiscard = editSummary?.editions?.firstOrNull { it.eventId == redactedEventId }
        if (sourceToDiscard == null) {
            Timber.w("Redaction of a replace that was not known in aggregation")
            return
        }
        editSummary.editions.remove(sourceToDiscard)
        // Touch event_annotations_summary so the timeline's annotation-change flow (which only watches that
        // table, not the editions table) fires — same reason as handleReactionRedact.
        stores.annotations.upsertSummary(relatedEventId, eventSummary.roomId)
        stores.annotations.replaceEditions(relatedEventId, editSummary)
    }

    private fun handleReactionRedact(stores: SessionStores, eventToPrune: EventEntity) {
        Timber.v("REDACTION of reaction ${eventToPrune.eventId}")
        // delete a reaction, need to update the annotation summary if any
        val reactionContent: ReactionContent = eventToPrune.asDomain().content.toModel() ?: return
        val eventThatWasReacted = reactionContent.relatesTo?.eventId ?: return
        val reactionKey = reactionContent.relatesTo.key
        Timber.v("REMOVE reaction for key $reactionKey")
        val summary = stores.annotations.get(eventThatWasReacted)
        if (summary == null) {
            Timber.e("## Cannot find summary for key $reactionKey")
            return
        }
        val aggregation = summary.reactionsSummary.firstOrNull { it.key == reactionKey } ?: return
        if (!aggregation.sourceEvents.contains(eventToPrune.eventId)) {
            Timber.e("## Cannot remove summary from count, corresponding reaction ${eventToPrune.eventId} is not known")
            return
        }
        aggregation.sourceEvents.remove(eventToPrune.eventId)
        refreshReactionSummary(stores, aggregation)
        if (aggregation.count == 0) {
            summary.reactionsSummary.remove(aggregation)
        }
        // Touch event_annotations_summary too: reactions live in their own table, but the timeline's
        // annotation-change flow only watches event_annotations_summary. Without this the removal writes only
        // the reactions table and the timeline never re-maps, leaving the redacted reaction shown until reopen
        // (the add path does the same via upsertSummary).
        stores.annotations.upsertSummary(eventThatWasReacted, eventToPrune.roomId)
        stores.annotations.replaceReactions(eventThatWasReacted, summary.reactionsSummary)
    }

    private fun handleVerification(stores: SessionStores, event: Event, roomId: String, isLocalEcho: Boolean, relatedEventId: String) {
        val eventSummary = stores.annotations.get(relatedEventId)
                ?: EventAnnotationsSummaryEntity(eventId = relatedEventId, roomId = roomId)

        val verifSummary = eventSummary.referencesSummaryEntity
                ?: ReferencesAggregatedSummaryEntity(eventId = relatedEventId).also {
                    eventSummary.referencesSummaryEntity = it
                }

        val txId = event.unsignedData?.transactionId

        if (!isLocalEcho && verifSummary.sourceLocalEcho.contains(txId)) {
            // ok it has already been handled
        } else {
            var data = ContentMapper.map(verifSummary.content)?.toModel<ReferencesAggregatedContent>()
                    ?: ReferencesAggregatedContent(VerificationState.REQUEST)
            // never change state if already canceled/done (handled by toState)
            val currentState = data.verificationState
            val newState = when (event.getClearType()) {
                EventType.KEY_VERIFICATION_START,
                EventType.KEY_VERIFICATION_ACCEPT,
                EventType.KEY_VERIFICATION_READY,
                EventType.KEY_VERIFICATION_KEY,
                EventType.KEY_VERIFICATION_MAC -> currentState.toState(VerificationState.WAITING)
                EventType.KEY_VERIFICATION_CANCEL -> currentState.toState(
                        if (event.senderId == userId) VerificationState.CANCELED_BY_ME else VerificationState.CANCELED_BY_OTHER
                )
                EventType.KEY_VERIFICATION_DONE -> currentState.toState(VerificationState.DONE)
                else -> VerificationState.REQUEST
            }
            data = data.copy(verificationState = newState)
            verifSummary.content = ContentMapper.map(data.toContent())
        }

        if (isLocalEcho) {
            event.eventId?.let { verifSummary.sourceLocalEcho.add(it) }
        } else {
            verifSummary.sourceLocalEcho.remove(txId)
            event.eventId?.let { verifSummary.sourceEvents.add(it) }
        }

        stores.annotations.upsertSummary(relatedEventId, roomId)
        stores.annotations.upsertReferences(relatedEventId, verifSummary)
    }
}
