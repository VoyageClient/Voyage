/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.aggregation.poll

import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.getRelationContent
import org.matrix.android.sdk.api.session.events.model.isRedacted
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.PollSummaryContent
import org.matrix.android.sdk.api.session.room.model.VoteInfo
import org.matrix.android.sdk.api.session.room.model.VoteSummary
import org.matrix.android.sdk.api.session.room.model.message.MessagePollResponseContent
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.UserId
import javax.inject.Inject

internal class PollResponseSummaryRefresher @Inject constructor(
        @UserId private val userId: String,
) {

    fun remove(stores: SessionStores, event: Event) {
        val type = event.getClearType()
        if (type !in EventType.POLL_RESPONSE.values && type != EventType.ENCRYPTED) return
        val removedIds = setOfNotNull(event.eventId, event.unsignedData?.transactionId)
        val pollEventId = event.getRelationContent()?.eventId?.takeIf { type in EventType.POLL_RESPONSE.values }
                ?: removedIds.firstNotNullOfOrNull { stores.annotations.findPollAnnotationIdBySourceEvent(it) }
                ?: return
        val roomId = event.roomId ?: return
        val senderId = event.senderId ?: return
        val summary = stores.annotations.get(pollEventId) ?: return
        if (summary.roomId != roomId) return
        val poll = summary.pollResponseSummary ?: return
        val removed = poll.sourceEvents.removeAll(removedIds) or poll.sourceLocalEchoEvents.removeAll(removedIds)
        if (!removed) return
        val previous = (poll.sourceEvents + poll.sourceLocalEchoEvents).asSequence()
                .mapNotNull { stores.event.getByEventIdInRoom(roomId, it)?.asDomain() }
                .filter { it.senderId == senderId && !it.isRedacted() && it.getClearType() in EventType.POLL_RESPONSE.values }
                .filter { it.getClearContent().toModel<MessagePollResponseContent>()?.relatesTo?.eventId == pollEventId }
                .maxByOrNull { it.originServerTs ?: 0 }
        val votes = ContentMapper.map(poll.aggregatedContent).toModel<PollSummaryContent>()
                ?.votes.orEmpty().filter { it.userId != senderId }.toMutableList()
        previous?.let { response ->
            val answer = response.getClearContent().toModel<MessagePollResponseContent>()?.getBestResponse()?.answers?.firstOrNull()
            if (answer != null) votes.add(VoteInfo(senderId, answer, response.originServerTs ?: 0))
        }
        val totals = votes.groupingBy { it.option }.eachCount()
        val content = PollSummaryContent(
                myVote = votes.firstOrNull { it.userId == userId }?.option,
                votes = votes,
                votesSummary = totals.mapValues { VoteSummary(it.value, it.value.toDouble() / votes.size) },
                totalVotes = votes.size,
                winnerVoteCount = totals.values.maxOrNull() ?: 0
        )
        poll.aggregatedContent = ContentMapper.map(content.toContent())
        stores.annotations.upsertSummary(pollEventId, roomId)
        stores.annotations.upsertPollResponse(pollEventId, poll)
        stores.timelineEvent.touch(pollEventId)
    }
}
