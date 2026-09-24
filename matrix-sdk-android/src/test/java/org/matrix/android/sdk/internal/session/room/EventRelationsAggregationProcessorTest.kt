/*
 * Copyright (c) 2022 The Matrix.org Foundation C.I.C.
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

import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.api.session.events.model.content.EncryptedEventContent
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.room.model.relation.ReactionContent
import org.matrix.android.sdk.api.session.room.model.relation.ReactionInfo
import org.matrix.android.sdk.api.session.room.model.relation.RelationDefaultContent
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.internal.database.mapper.toEntity
import org.matrix.android.sdk.internal.session.room.relation.ReactionSummaryRefresher
import org.matrix.android.sdk.internal.session.room.summary.RoomSummaryPreviewInvalidation
import org.matrix.android.sdk.test.fakes.FakeClock
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.matrix.android.sdk.test.fakes.FakeStateEventDataSource
import org.matrix.android.sdk.test.fakes.internal.FakeEventEditValidator
import org.matrix.android.sdk.test.fakes.internal.FakeLiveLocationAggregationProcessor
import org.matrix.android.sdk.test.fakes.internal.FakePollAggregationProcessor
import org.matrix.android.sdk.test.fakes.internal.FakeSessionManager
import org.matrix.android.sdk.test.fakes.internal.session.room.aggregation.utd.FakeEncryptedReferenceAggregationProcessor
import org.robolectric.RobolectricTestRunner

private const val A_ROOM_ID = "room-id"
private const val AN_EVENT_ID = "event-id"
private const val A_TARGET_ID = "\$target"
private const val AN_ECHO_ID = "\$local.reaction"

@RunWith(RobolectricTestRunner::class)
internal class EventRelationsAggregationProcessorTest {

    private val fakeStateEventDataSource = FakeStateEventDataSource()
    private val fakeSessionManager = FakeSessionManager()
    private val fakeLiveLocationAggregationProcessor = FakeLiveLocationAggregationProcessor()
    private val fakePollAggregationProcessor = FakePollAggregationProcessor()
    private val fakeEncryptedReferenceAggregationProcessor = FakeEncryptedReferenceAggregationProcessor()
    private val fakeEventEditValidator = FakeEventEditValidator()
    private val fakeClock = FakeClock()
    private val db = FakeSessionDatabase()

    private val encryptedEventRelationsAggregationProcessor = EventRelationsAggregationProcessor(
            userId = "userId",
            stateEventDataSource = fakeStateEventDataSource.instance,
            sessionId = "sessionId",
            sessionManager = fakeSessionManager.instance,
            liveLocationAggregationProcessor = fakeLiveLocationAggregationProcessor.instance,
            pollAggregationProcessor = fakePollAggregationProcessor.instance,
            encryptedReferenceAggregationProcessor = fakeEncryptedReferenceAggregationProcessor.instance,
            editValidator = fakeEventEditValidator.instance,
            clock = fakeClock,
            previewInvalidation = RoomSummaryPreviewInvalidation(),
            reactionSummaryRefresher = ReactionSummaryRefresher(userId = "userId"),
    )

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `given an encrypted reference event when process then reference is processed`() {
        // Given
        val anEvent = givenAnEvent(
                eventId = AN_EVENT_ID,
                roomId = A_ROOM_ID,
                eventType = EventType.ENCRYPTED,
        )
        val relatedEventId = "related-event-id"
        val encryptedEventContent = givenEncryptedEventContent(
                relationType = RelationType.REFERENCE,
                relatedEventId = relatedEventId,
        )
        every { anEvent.content } returns encryptedEventContent.toContent()
        val resultOfReferenceProcess = false
        fakeEncryptedReferenceAggregationProcessor.givenHandleReturns(resultOfReferenceProcess)

        // When
        encryptedEventRelationsAggregationProcessor.process(
                stores = db.stores,
                event = anEvent,
        )

        // Then
        fakeEncryptedReferenceAggregationProcessor.verifyHandle(
                stores = db.stores,
                event = anEvent,
                isLocalEcho = false,
                relatedEventId = relatedEventId,
        )
    }

    @Test
    fun `given a pending reaction echo, when it is redacted before syncing, then the reaction is removed`() {
        givenPendingReactionEcho()

        processReal(Event(type = EventType.REDACTION, eventId = "\$local.redaction", roomId = A_ROOM_ID, senderId = "userId", redacts = AN_ECHO_ID))

        db.stores.annotations.get(A_TARGET_ID)?.reactionsSummary.orEmpty().shouldBeEmpty()
    }

    @Test
    fun `given a pending reaction echo, when its synced copy arrives already redacted, then the echo is dropped`() {
        givenPendingReactionEcho()

        processReal(
                Event(
                        type = EventType.REACTION,
                        eventId = "\$remote",
                        roomId = A_ROOM_ID,
                        senderId = "userId",
                        content = emptyMap(),
                        unsignedData = UnsignedData(age = null, transactionId = AN_ECHO_ID),
                )
        )

        db.stores.annotations.get(A_TARGET_ID)?.reactionsSummary.orEmpty().shouldBeEmpty()
    }

    @Test
    fun `given a synced reaction whose content was pruned, when its redaction arrives, then its echo is dropped too`() {
        givenPendingReactionEcho()
        val remote = Event(
                type = EventType.REACTION,
                eventId = "\$remote",
                roomId = A_ROOM_ID,
                senderId = "userId",
                content = emptyMap(),
                unsignedData = UnsignedData(age = null, transactionId = AN_ECHO_ID),
        )
        db.stores.event.insert(remote.toEntity(A_ROOM_ID, SendState.SYNCED, 0L))

        processReal(Event(type = EventType.REDACTION, eventId = "\$redaction", roomId = A_ROOM_ID, senderId = "userId", redacts = "\$remote"))

        db.stores.annotations.get(A_TARGET_ID)?.reactionsSummary.orEmpty().shouldBeEmpty()
    }

    @Test
    fun `given a reaction un-reacted as an echo, when its synced copy arrives before its redaction, then it is not counted`() {
        givenPendingReactionEcho()
        val redaction = Event(type = EventType.REDACTION, eventId = "\$local.redaction", roomId = A_ROOM_ID, senderId = "userId", redacts = AN_ECHO_ID)
        db.stores.event.insert(redaction.toEntity(A_ROOM_ID, SendState.SENT, 0L))
        processReal(redaction)

        processReal(
                Event(
                        type = EventType.REACTION,
                        eventId = "\$remote",
                        roomId = A_ROOM_ID,
                        senderId = "userId",
                        content = ReactionContent(ReactionInfo(RelationType.ANNOTATION, A_TARGET_ID, "👍")).toContent(),
                        unsignedData = UnsignedData(age = null, transactionId = AN_ECHO_ID),
                )
        )

        db.stores.annotations.get(A_TARGET_ID)?.reactionsSummary.orEmpty().shouldBeEmpty()
    }

    private fun givenPendingReactionEcho() {
        val echo = Event(
                type = EventType.REACTION,
                eventId = AN_ECHO_ID,
                roomId = A_ROOM_ID,
                senderId = "userId",
                content = ReactionContent(ReactionInfo(RelationType.ANNOTATION, A_TARGET_ID, "👍")).toContent(),
                unsignedData = UnsignedData(age = null, transactionId = AN_ECHO_ID),
        )
        db.stores.event.insert(echo.toEntity(A_ROOM_ID, SendState.SENT, 0L))
        processReal(echo)
        db.stores.annotations.get(A_TARGET_ID)!!.reactionsSummary.single().sourceLocalEcho shouldBeEqualTo listOf(AN_ECHO_ID)
    }

    private fun processReal(event: Event) = db.database.transaction {
        encryptedEventRelationsAggregationProcessor.process(stores = db.stores, event = event)
    }

    private fun givenAnEvent(
            eventId: String,
            roomId: String?,
            eventType: String,
    ): Event {
        return mockk<Event>().also {
            every { it.eventId } returns eventId
            every { it.roomId } returns roomId
            every { it.getClearType() } returns eventType
        }
    }

    private fun givenEncryptedEventContent(relationType: String, relatedEventId: String): EncryptedEventContent {
        val relationContent = RelationDefaultContent(
                eventId = relatedEventId,
                type = relationType,
        )
        return EncryptedEventContent(
                relatesTo = relationContent,
        )
    }
}
