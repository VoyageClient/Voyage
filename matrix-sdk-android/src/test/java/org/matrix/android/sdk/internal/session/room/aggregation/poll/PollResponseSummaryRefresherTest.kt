/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.aggregation.poll

import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.crypto.model.OlmDecryptionResult
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.PollSummaryContent
import org.matrix.android.sdk.api.session.room.model.VoteInfo
import org.matrix.android.sdk.api.session.room.model.message.MessagePollResponseContent
import org.matrix.android.sdk.api.session.room.model.message.PollResponse
import org.matrix.android.sdk.api.session.room.model.relation.RelationDefaultContent
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.mapper.EventMapper
import org.matrix.android.sdk.internal.database.model.PollResponseAggregatedSummaryEntity
import org.matrix.android.sdk.internal.session.room.prune.RedactionEventProcessor
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PollResponseSummaryRefresherTest {

    private val db = FakeSessionDatabase()
    private val refresher = PollResponseSummaryRefresher("me")
    private val roomId = "!room:example.org"
    private val pollId = "poll"

    @After
    fun tearDown() = db.close()

    @Test
    fun `redacting only vote clears selection and tally`() {
        val vote = vote("vote", "me", "pizza", 1)
        seed(listOf(vote))
        refresher.remove(db.stores, vote)
        val result = summary()
        assertNull(result.myVote)
        assertEquals(0, result.totalVotes)
        assertEquals(0, result.winnerVoteCount)
        assertEquals(emptyMap<String, Any>(), result.votesSummary)
    }

    @Test
    fun `already pruned response is found through its tracked source id`() {
        val vote = vote("vote", "me", "pizza", 1)
        seed(listOf(vote))
        refresher.remove(db.stores, vote.copy(content = emptyMap()))
        assertNull(summary().myVote)
        assertEquals(0, summary().totalVotes)
    }

    @Test
    fun `redacting latest vote restores previous choice and preserves other voters`() {
        val older = vote("older", "me", "pizza", 1)
        val latest = vote("latest", "me", "pasta", 2)
        val other = vote("other", "them", "pasta", 3)
        seed(listOf(older, latest, other))
        refresher.remove(db.stores, latest)
        val result = summary()
        assertEquals("pizza", result.myVote)
        assertEquals(2, result.totalVotes)
        assertEquals(0.5, result.votesSummary!!["pizza"]!!.percentage, 0.0)
        assertEquals(1, result.winnerVoteCount)
    }

    @Test
    fun `redacting older vote leaves latest vote selected`() {
        val older = vote("older", "me", "pizza", 1)
        val latest = vote("latest", "me", "pasta", 2)
        seed(listOf(older, latest))
        refresher.remove(db.stores, older)
        assertEquals("pasta", summary().myVote)
        assertEquals(1, summary().totalVotes)
    }

    @Test
    fun `redacting all own votes clears selection without removing other voters`() {
        val older = vote("older", "me", "pizza", 1)
        val latest = vote("latest", "me", "pasta", 2)
        val other = vote("other", "them", "pasta", 3)
        seed(listOf(older, latest, other))
        refresher.remove(db.stores, older)
        refresher.remove(db.stores, latest)
        assertNull(summary().myVote)
        assertEquals(1, summary().totalVotes)
    }

    @Test
    fun `pruning encrypted response refreshes closed poll and drops matching local echo`() {
        val plain = vote("vote", "me", "pizza", 1)
        val encrypted = plain.copy(
                type = EventType.ENCRYPTED,
                content = emptyMap(),
                unsignedData = UnsignedData(age = null, transactionId = "transaction")
        ).apply {
            mxDecryptionResult = OlmDecryptionResult(payload = mapOf("type" to plain.type!!, "content" to plain.content!!))
        }
        seed(listOf(encrypted))
        val poll = db.stores.annotations.get(pollId)!!.pollResponseSummary!!
        poll.closedTime = 5
        poll.sourceLocalEchoEvents.add("transaction")
        db.stores.annotations.upsertPollResponse(pollId, poll)
        val processor = RedactionEventProcessor(
                roomSummaryUpdater = mockk(relaxed = true),
                eventIndexer = mockk(relaxed = true),
                previewInvalidation = mockk(relaxed = true),
                timelineRedactionSignal = mockk(relaxed = true),
                pollResponseSummaryRefresher = refresher
        )
        processor.prune(db.stores, Event(roomId = roomId, eventId = "redaction", redacts = "vote"))
        assertEquals(0, summary().totalVotes)
        val persisted = db.stores.annotations.get(pollId)!!.pollResponseSummary!!
        assertEquals(5L, persisted.closedTime)
        assertEquals(emptyList<String>(), persisted.sourceLocalEchoEvents)
    }

    private fun vote(id: String, sender: String, answer: String, timestamp: Long): Event {
        val content = MessagePollResponseContent(
                unstableResponse = PollResponse(answers = listOf(answer)),
                relatesTo = RelationDefaultContent(RelationType.REFERENCE, pollId)
        )
        return Event(
                roomId = roomId,
                eventId = id,
                senderId = sender,
                type = EventType.POLL_RESPONSE.unstable,
                originServerTs = timestamp,
                content = content.toContent()
        )
    }

    private fun seed(events: List<Event>) {
        events.forEach { db.stores.event.insert(EventMapper.map(it, roomId)) }
        val votes = events.groupBy { it.senderId }.values.map { responses ->
            val event = responses.maxBy { it.originServerTs!! }
            val answer = event.getClearContent().toModel<MessagePollResponseContent>()!!.getBestResponse()!!.answers!!.first()
            VoteInfo(event.senderId!!, answer, event.originServerTs!!)
        }
        val content = PollSummaryContent(myVote = votes.firstOrNull { it.userId == "me" }?.option, votes = votes, totalVotes = votes.size)
        db.stores.annotations.upsertSummary(pollId, roomId)
        db.stores.annotations.upsertPollResponse(pollId, PollResponseAggregatedSummaryEntity(
                aggregatedContent = ContentMapper.map(content.toContent()),
                sourceEvents = events.map { it.eventId!! }.toMutableList()
        ))
    }

    private fun summary(): PollSummaryContent =
            ContentMapper.map(db.stores.annotations.get(pollId)!!.pollResponseSummary!!.aggregatedContent).toModel<PollSummaryContent>()!!
}
