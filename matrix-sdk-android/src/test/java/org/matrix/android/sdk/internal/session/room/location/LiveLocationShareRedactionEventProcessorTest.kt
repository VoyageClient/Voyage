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

package org.matrix.android.sdk.internal.session.room.location

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBe
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.EventInsertType
import org.matrix.android.sdk.internal.database.model.livelocation.LiveLocationShareAggregatedSummaryEntity
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.robolectric.RobolectricTestRunner

private const val AN_EVENT_ID = "event-id"
private const val A_REDACTED_EVENT_ID = "redacted-event-id"
private const val A_ROOM_ID = "room-id"

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class LiveLocationShareRedactionEventProcessorTest {

    private val liveLocationShareRedactionEventProcessor = LiveLocationShareRedactionEventProcessor()
    private val db = FakeSessionDatabase()

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `given an event when checking if it should be processed then only event of type REDACTED is processed`() {
        val result = liveLocationShareRedactionEventProcessor.shouldProcess(
                eventId = AN_EVENT_ID,
                eventType = EventType.REDACTION,
                insertType = EventInsertType.INCREMENTAL_SYNC
        )

        result shouldBe true
    }

    @Test
    fun `given an event when checking if it should be processed then local echo is not processed`() {
        val result = liveLocationShareRedactionEventProcessor.shouldProcess(
                eventId = AN_EVENT_ID,
                eventType = EventType.REDACTION,
                insertType = EventInsertType.LOCAL_ECHO
        )

        result shouldBe false
    }

    @Test
    fun `given a redacted live location share event when processing it then related summaries are deleted from database`() = runTest {
        val event = Event(eventId = AN_EVENT_ID, redacts = A_REDACTED_EVENT_ID)
        db.stores.event.insert(EventEntity(
                eventId = A_REDACTED_EVENT_ID, roomId = A_ROOM_ID, type = EventType.STATE_ROOM_BEACON_INFO.unstable))
        db.stores.liveLocation.upsert(LiveLocationShareAggregatedSummaryEntity(eventId = A_REDACTED_EVENT_ID, roomId = A_ROOM_ID))
        db.stores.annotations.upsertSummary(A_REDACTED_EVENT_ID, A_ROOM_ID)

        liveLocationShareRedactionEventProcessor.process(db.stores, event = event)

        (db.stores.liveLocation.get(A_REDACTED_EVENT_ID) == null) shouldBe true
        (db.stores.annotations.get(A_REDACTED_EVENT_ID) == null) shouldBe true
    }
}
