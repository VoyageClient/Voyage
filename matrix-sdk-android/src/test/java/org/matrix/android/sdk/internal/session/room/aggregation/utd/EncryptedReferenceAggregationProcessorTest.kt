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

package org.matrix.android.sdk.internal.session.room.aggregation.utd

import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContain
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.internal.database.model.PollResponseAggregatedSummaryEntity
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.robolectric.RobolectricTestRunner

private const val AN_ANNOTATION_EVENT_ID = "annotation-event-id"
private const val A_ROOM_ID = "room-id"

@RunWith(RobolectricTestRunner::class)
internal class EncryptedReferenceAggregationProcessorTest {

    private val db = FakeSessionDatabase()

    private val encryptedReferenceAggregationProcessor = EncryptedReferenceAggregationProcessor()

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `given local echo when process then result is false`() {
        val result = encryptedReferenceAggregationProcessor.handle(
                stores = db.stores,
                event = mockk(),
                isLocalEcho = true,
                relatedEventId = "event-id",
        )

        result.shouldBeFalse()
    }

    @Test
    fun `given invalid event id when process then result is false`() {
        val anEvent = mockk<Event>()

        val result1 = encryptedReferenceAggregationProcessor.handle(
                stores = db.stores, event = anEvent, isLocalEcho = false, relatedEventId = null)
        val result2 = encryptedReferenceAggregationProcessor.handle(
                stores = db.stores, event = anEvent, isLocalEcho = false, relatedEventId = "")

        result1.shouldBeFalse()
        result2.shouldBeFalse()
    }

    @Test
    fun `given related event id of an existing poll when process then result is true and event id is stored in poll summary`() {
        // Given
        val anEventId = "event-id"
        val anEvent = givenAnEvent(anEventId)
        val relatedEventId = "related-event-id"
        givenAPollResponseWithSourceEvent(relatedEventId)

        // When
        val result = encryptedReferenceAggregationProcessor.handle(
                stores = db.stores,
                event = anEvent,
                isLocalEcho = false,
                relatedEventId = relatedEventId,
        )

        // Then
        result.shouldBeTrue()
        db.stores.annotations.get(AN_ANNOTATION_EVENT_ID)!!.pollResponseSummary!!.encryptedRelatedEventIds.shouldContain(anEventId)
    }

    @Test
    fun `given related event id but no existing related poll when process then result is true and event id is not stored`() {
        val anEvent = givenAnEvent("event-id")

        val result = encryptedReferenceAggregationProcessor.handle(
                stores = db.stores,
                event = anEvent,
                isLocalEcho = false,
                relatedEventId = "related-event-id",
        )

        result.shouldBeTrue()
    }

    private fun givenAPollResponseWithSourceEvent(sourceEventId: String) {
        db.stores.annotations.upsertSummary(AN_ANNOTATION_EVENT_ID, A_ROOM_ID)
        db.stores.annotations.upsertPollResponse(AN_ANNOTATION_EVENT_ID, PollResponseAggregatedSummaryEntity(
                sourceEvents = mutableListOf(sourceEventId),
                encryptedRelatedEventIds = mutableListOf(),
        ))
    }

    private fun givenAnEvent(eventId: String): Event {
        return mockk<Event>().also {
            every { it.eventId } returns eventId
            every { it.roomId } returns A_ROOM_ID
        }
    }
}
