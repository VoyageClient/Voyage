/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.relation

import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.relation.ReactionContent
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.model.ReactionAggregatedSummaryEntity
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.UserId
import javax.inject.Inject

/**
 * Keeps a reaction's [ReactionAggregatedSummaryEntity.count] and
 * [ReactionAggregatedSummaryEntity.addedByMe] derived from the source lists, so they can never drift
 * out of sync with the reactions actually known (which is what causes inflated counters and stale
 * highlight state).
 *
 * Ignored users' reactions stay stored but stop counting, so (un)ignoring flips them back and forth
 * without re-syncing. A zero count is what hides the reaction (see EventAnnotationsSummaryMapper).
 */
internal class ReactionSummaryRefresher @Inject constructor(
        @UserId private val userId: String,
) {

    fun refresh(stores: SessionStores, sum: ReactionAggregatedSummaryEntity) {
        val ignored = stores.user.getIgnoredUserIds().toSet()
        val senders = sum.sourceEvents.map { stores.event.getByEventId(it)?.sender }
        sum.count = senders.count { it !in ignored } + sum.sourceLocalEcho.size
        // Local echoes are always our own; otherwise look up the sender of each known source event.
        sum.addedByMe = sum.sourceLocalEcho.isNotEmpty() || senders.any { it == userId }
    }

    /** Re-counts every reaction the given users left, after the ignore list changed. */
    fun refreshFromSenders(stores: SessionStores, senderIds: Collection<String>) {
        // ENCRYPTED too: a reaction sent into an encrypted room is stored under that type, and its
        // m.reaction only shows in the decrypted payload.
        stores.event.getByTypesAndSenders(listOf(EventType.REACTION, EventType.ENCRYPTED), senderIds)
                .map { it.asDomain() }
                .filter { it.getClearType() == EventType.REACTION }
                .mapNotNull { it.getClearContent().toModel<ReactionContent>()?.relatesTo }
                .filter { it.type == RelationType.ANNOTATION }
                .groupBy({ it.eventId }, { it.key })
                .forEach { (relatedEventId, keys) ->
                    val summary = stores.annotations.get(relatedEventId) ?: return@forEach
                    summary.reactionsSummary
                            .filter { it.key in keys }
                            .forEach { refresh(stores, it) }
                    // The timeline's annotation-change flow watches the parent row only.
                    stores.annotations.upsertSummary(relatedEventId, summary.roomId)
                    stores.annotations.replaceReactions(relatedEventId, summary.reactionsSummary)
                }
    }
}
