/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.internal.database.model.EditAggregatedSummaryEntity
import org.matrix.android.sdk.internal.database.model.EditionOfEvent
import org.matrix.android.sdk.internal.database.model.EventAnnotationsSummaryEntity
import org.matrix.android.sdk.internal.database.model.PollResponseAggregatedSummaryEntity
import org.matrix.android.sdk.internal.database.model.ReactionAggregatedSummaryEntity
import org.matrix.android.sdk.internal.database.model.ReferencesAggregatedSummaryEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.Edition_of_event as EditionRow
import org.matrix.android.sdk.internal.database.sql.Event_annotations_summary as AnnotationsRow
import org.matrix.android.sdk.internal.database.sql.Poll_response_aggregated_summary as PollRow
import org.matrix.android.sdk.internal.database.sql.Reaction_aggregated_summary as ReactionRow
import org.matrix.android.sdk.internal.database.sql.References_aggregated_summary as ReferencesRow

/**
 * SQL access for `event_annotations_summary` and its aggregation children (reactions, editions,
 * references, poll responses). The 1-1 live-location summary is resolved by [LiveLocationSqlStore].
 */
internal class EventAnnotationsSqlStore(
        private val database: SessionSqlDatabase,
        private val liveLocationStore: LiveLocationSqlStore,
) {

    private val queries get() = database.eventAnnotationsSummaryQueries

    fun get(eventId: String): EventAnnotationsSummaryEntity? = queries.selectSummary(eventId).executeAsOneOrNull()?.toEntity()

    fun upsertSummary(eventId: String, roomId: String?) = queries.upsertSummary(eventId, roomId)

    fun replaceReactions(eventId: String, reactions: List<ReactionAggregatedSummaryEntity>) {
        queries.deleteReactions(eventId)
        reactions.forEachIndexed { index, r ->
            queries.insertReaction(
                    annotation_event_id = eventId,
                    reaction_order = index.toLong(),
                    reaction_key = r.key,
                    reaction_count = r.count.toLong(),
                    added_by_me = if (r.addedByMe) 1L else 0L,
                    first_timestamp = r.firstTimestamp,
                    source_events = r.sourceEvents.toList().joinToColumn(),
                    source_local_echo = r.sourceLocalEcho.toList().joinToColumn(),
            )
        }
    }

    fun replaceEditions(eventId: String, editSummary: EditAggregatedSummaryEntity?) {
        queries.deleteEditions(eventId)
        editSummary?.editions?.forEachIndexed { index, e ->
            queries.insertEdition(
                    annotation_event_id = eventId,
                    edition_order = index.toLong(),
                    event_id = e.eventId,
                    timestamp = e.timestamp,
                    is_local_echo = if (e.isLocalEcho) 1L else 0L,
                    event_db_id = null,
            )
        }
    }

    fun upsertReferences(eventId: String, references: ReferencesAggregatedSummaryEntity?) {
        if (references == null) {
            queries.deleteReferences(eventId)
        } else {
            queries.upsertReferences(
                    annotation_event_id = eventId,
                    event_id = references.eventId,
                    content = references.content,
                    source_events = references.sourceEvents.toList().joinToColumn(),
                    source_local_echo = references.sourceLocalEcho.toList().joinToColumn(),
            )
        }
    }

    /** Find the annotation event id of the poll summary whose source events include [sourceEventId]. */
    fun findPollAnnotationIdBySourceEvent(sourceEventId: String): String? =
            queries.selectPollResponsesBySourceEventLike(sourceEventId).executeAsList()
                    .firstOrNull { sourceEventId in it.source_events.splitToList() }
                    ?.annotation_event_id

    fun upsertPollResponse(eventId: String, poll: PollResponseAggregatedSummaryEntity?) {
        if (poll == null) {
            queries.deletePollResponse(eventId)
        } else {
            queries.upsertPollResponse(
                    annotation_event_id = eventId,
                    aggregated_content = poll.aggregatedContent,
                    closed_time = poll.closedTime,
                    nb_options = poll.nbOptions.toLong(),
                    source_events = poll.sourceEvents.toList().joinToColumn(),
                    source_local_echo_events = poll.sourceLocalEchoEvents.toList().joinToColumn(),
                    encrypted_related_event_ids = poll.encryptedRelatedEventIds.toList().joinToColumn(),
            )
        }
    }

    fun delete(eventId: String) {
        queries.deleteReactions(eventId)
        queries.deleteEditions(eventId)
        queries.deleteReferences(eventId)
        queries.deletePollResponse(eventId)
        queries.deleteSummary(eventId)
    }

    private fun AnnotationsRow.toEntity(): EventAnnotationsSummaryEntity {
        val reactions = queries.selectReactions(event_id).executeAsList().map { it.toEntity() }
        val editions = queries.selectEditions(event_id).executeAsList().map { it.toEntity() }
        val references = queries.selectReferences(event_id).executeAsOneOrNull()?.toEntity()
        val poll = queries.selectPollResponse(event_id).executeAsOneOrNull()?.toEntity()
        return EventAnnotationsSummaryEntity(
                eventId = event_id,
                roomId = room_id,
                reactionsSummary = ArrayList<ReactionAggregatedSummaryEntity>().apply { addAll(reactions) },
                editSummary = editions.takeIf { it.isNotEmpty() }?.let {
                    EditAggregatedSummaryEntity(editions = ArrayList<EditionOfEvent>().apply { addAll(it) })
                },
                referencesSummaryEntity = references,
                pollResponseSummary = poll,
                liveLocationShareAggregatedSummary = liveLocationStore.get(event_id),
        )
    }

    private fun ReactionRow.toEntity(): ReactionAggregatedSummaryEntity = ReactionAggregatedSummaryEntity(
            key = reaction_key,
            count = reaction_count.toInt(),
            addedByMe = added_by_me != 0L,
            firstTimestamp = first_timestamp,
            sourceEvents = source_events.splitToRealmList(),
            sourceLocalEcho = source_local_echo.splitToRealmList(),
    )

    private fun EditionRow.toEntity(): EditionOfEvent = EditionOfEvent(
            eventId = event_id,
            timestamp = timestamp,
            isLocalEcho = is_local_echo != 0L,
            event = null,
    )

    private fun ReferencesRow.toEntity(): ReferencesAggregatedSummaryEntity = ReferencesAggregatedSummaryEntity(
            eventId = event_id,
            content = content,
            sourceEvents = source_events.splitToRealmList(),
            sourceLocalEcho = source_local_echo.splitToRealmList(),
    )

    private fun PollRow.toEntity(): PollResponseAggregatedSummaryEntity = PollResponseAggregatedSummaryEntity(
            aggregatedContent = aggregated_content,
            closedTime = closed_time,
            nbOptions = nb_options.toInt(),
            sourceEvents = source_events.splitToRealmList(),
            sourceLocalEchoEvents = source_local_echo_events.splitToRealmList(),
            encryptedRelatedEventIds = encrypted_related_event_ids.splitToRealmList(),
    )
}
