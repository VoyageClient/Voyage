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

package org.matrix.android.sdk.internal.session.room.aggregation.livelocation

import androidx.work.ExistingWorkPolicy
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.LocationInfo
import org.matrix.android.sdk.api.session.room.model.message.MessageBeaconInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageBeaconLocationDataContent
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.model.livelocation.LiveLocationShareAggregatedSummaryEntity
import org.matrix.android.sdk.test.fakes.FakeClock
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.matrix.android.sdk.test.fakes.FakeWorkManagerProvider
import org.robolectric.RobolectricTestRunner

private const val A_SESSION_ID = "session_id"
private const val A_SENDER_ID = "sender_id"
private const val AN_EVENT_ID = "event_id"
private const val A_ROOM_ID = "room_id"
private const val A_TIMESTAMP = 1654689143L
private const val A_TIMEOUT_MILLIS = 15 * 60 * 1000L
private const val A_LATITUDE = 40.05
private const val A_LONGITUDE = 29.24
private const val A_UNCERTAINTY = 30.0
private const val A_GEO_URI = "geo:$A_LATITUDE,$A_LONGITUDE;u=$A_UNCERTAINTY"

@RunWith(RobolectricTestRunner::class)
internal class LiveLocationAggregationProcessorTest {

    private val fakeWorkManagerProvider = FakeWorkManagerProvider()
    private val fakeClock = FakeClock()
    private val db = FakeSessionDatabase()

    private val liveLocationAggregationProcessor = LiveLocationAggregationProcessor(
            sessionId = A_SESSION_ID,
            workManagerProvider = fakeWorkManagerProvider.instance,
            clock = fakeClock
    )

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `given beacon info when it is local echo then it is ignored`() {
        val event = Event(senderId = A_SENDER_ID)
        val beaconInfo = MessageBeaconInfoContent()

        val result = liveLocationAggregationProcessor.handleBeaconInfo(
                stores = db.stores,
                event = event,
                content = beaconInfo,
                roomId = A_ROOM_ID,
                isLocalEcho = true
        )

        result shouldBeEqualTo false
    }

    private data class IgnoredBeaconInfoEvent(
            val event: Event,
            val beaconInfo: MessageBeaconInfoContent
    )

    @Test
    fun `given beacon info and event when some values are missing then it is ignored`() {
        val ignoredInfoEvents = listOf(
                // missing senderId
                IgnoredBeaconInfoEvent(
                        event = Event(eventId = AN_EVENT_ID, senderId = null),
                        beaconInfo = MessageBeaconInfoContent()
                ),
                // empty senderId
                IgnoredBeaconInfoEvent(
                        event = Event(eventId = AN_EVENT_ID, senderId = ""),
                        beaconInfo = MessageBeaconInfoContent()
                ),
                // beacon is live and no eventId
                IgnoredBeaconInfoEvent(
                        event = Event(eventId = null, senderId = A_SENDER_ID),
                        beaconInfo = MessageBeaconInfoContent(isLive = true)
                ),
                // beacon is live and eventId is empty
                IgnoredBeaconInfoEvent(
                        event = Event(eventId = "", senderId = A_SENDER_ID),
                        beaconInfo = MessageBeaconInfoContent(isLive = true)
                ),
                // beacon is not live and replaced event id is null
                IgnoredBeaconInfoEvent(
                        event = Event(
                                eventId = AN_EVENT_ID,
                                senderId = A_SENDER_ID,
                                unsignedData = UnsignedData(
                                        age = 123,
                                        replacesState = null
                                )
                        ),
                        beaconInfo = MessageBeaconInfoContent(isLive = false)
                ),
                // beacon is not live and replaced event id is empty
                IgnoredBeaconInfoEvent(
                        event = Event(
                                eventId = AN_EVENT_ID,
                                senderId = A_SENDER_ID,
                                unsignedData = UnsignedData(
                                        age = 123,
                                        replacesState = ""
                                )
                        ),
                        beaconInfo = MessageBeaconInfoContent(isLive = false)
                ),
        )

        ignoredInfoEvents.forEach {
            val result = liveLocationAggregationProcessor.handleBeaconInfo(
                    stores = db.stores,
                    event = it.event,
                    content = it.beaconInfo,
                    roomId = A_ROOM_ID,
                    isLocalEcho = false
            )

            result shouldBeEqualTo false
        }
    }

    @Test
    fun `given beacon info and existing entity when beacon content is correct and active then it is aggregated`() {
        val event = Event(
                senderId = A_SENDER_ID,
                eventId = AN_EVENT_ID
        )
        val beaconInfo = MessageBeaconInfoContent(
                isLive = true,
                unstableTimestampMillis = A_TIMESTAMP,
                timeout = A_TIMEOUT_MILLIS
        )
        fakeClock.givenEpoch(A_TIMESTAMP + 5000)
        fakeWorkManagerProvider.fakeWorkManager.expectEnqueueUniqueWork()
        seedSummary(eventId = AN_EVENT_ID, roomId = A_ROOM_ID)
        val previousEventId = "${AN_EVENT_ID}1"
        seedPreviousActiveBeacon(previousEventId)

        val result = liveLocationAggregationProcessor.handleBeaconInfo(
                stores = db.stores,
                event = event,
                content = beaconInfo,
                roomId = A_ROOM_ID,
                isLocalEcho = false
        )

        result shouldBeEqualTo true
        val aggregatedEntity = db.stores.liveLocation.get(AN_EVENT_ID)!!
        aggregatedEntity.eventId shouldBeEqualTo AN_EVENT_ID
        aggregatedEntity.roomId shouldBeEqualTo A_ROOM_ID
        aggregatedEntity.userId shouldBeEqualTo A_SENDER_ID
        aggregatedEntity.isActive shouldBeEqualTo true
        aggregatedEntity.startOfLiveTimestampMillis shouldBeEqualTo A_TIMESTAMP
        aggregatedEntity.endOfLiveTimestampMillis shouldBeEqualTo A_TIMESTAMP + A_TIMEOUT_MILLIS
        aggregatedEntity.lastLocationContent shouldBeEqualTo null
        db.stores.liveLocation.get(previousEventId)!!.isActive shouldBeEqualTo false
        fakeWorkManagerProvider.fakeWorkManager.verifyEnqueueUniqueWork(
                workName = DeactivateLiveLocationShareWorker.getWorkName(eventId = AN_EVENT_ID, roomId = A_ROOM_ID),
                policy = ExistingWorkPolicy.REPLACE
        )
    }

    @Test
    fun `given beacon info and existing entity when beacon content is correct and inactive then it is aggregated`() {
        val unsignedData = UnsignedData(
                age = 123,
                replacesState = AN_EVENT_ID
        )
        val stateEventId = "state-event-id"
        val event = Event(
                senderId = A_SENDER_ID,
                eventId = stateEventId,
                unsignedData = unsignedData
        )
        val beaconInfo = MessageBeaconInfoContent(
                isLive = false,
                unstableTimestampMillis = A_TIMESTAMP,
                timeout = A_TIMEOUT_MILLIS
        )
        fakeClock.givenEpoch(A_TIMESTAMP + 5000)
        fakeWorkManagerProvider.fakeWorkManager.expectCancelUniqueWork()
        seedSummary(eventId = AN_EVENT_ID, roomId = A_ROOM_ID)
        val previousEventId = "${AN_EVENT_ID}1"
        seedPreviousActiveBeacon(previousEventId)

        val result = liveLocationAggregationProcessor.handleBeaconInfo(
                stores = db.stores,
                event = event,
                content = beaconInfo,
                roomId = A_ROOM_ID,
                isLocalEcho = false
        )

        result shouldBeEqualTo true
        val aggregatedEntity = db.stores.liveLocation.get(AN_EVENT_ID)!!
        aggregatedEntity.eventId shouldBeEqualTo AN_EVENT_ID
        aggregatedEntity.roomId shouldBeEqualTo A_ROOM_ID
        aggregatedEntity.userId shouldBeEqualTo A_SENDER_ID
        aggregatedEntity.isActive shouldBeEqualTo false
        aggregatedEntity.relatedEventIds shouldContain stateEventId
        aggregatedEntity.endOfLiveTimestampMillis shouldBeEqualTo A_TIMESTAMP + A_TIMEOUT_MILLIS
        aggregatedEntity.lastLocationContent shouldBeEqualTo null
        db.stores.liveLocation.get(previousEventId)!!.isActive shouldBeEqualTo false
        fakeWorkManagerProvider.fakeWorkManager.verifyCancelUniqueWork(
                workName = DeactivateLiveLocationShareWorker.getWorkName(eventId = AN_EVENT_ID, roomId = A_ROOM_ID)
        )
    }

    @Test
    fun `given beacon location data when it is local echo then it is ignored`() {
        val event = Event(senderId = A_SENDER_ID)
        val beaconLocationData = MessageBeaconLocationDataContent()

        val result = liveLocationAggregationProcessor.handleBeaconLocationData(
                stores = db.stores,
                event = event,
                content = beaconLocationData,
                roomId = A_ROOM_ID,
                relatedEventId = AN_EVENT_ID,
                isLocalEcho = true
        )

        result shouldBeEqualTo false
    }

    private data class IgnoredBeaconLocationDataEvent(
            val event: Event,
            val beaconLocationData: MessageBeaconLocationDataContent
    )

    @Test
    fun `given event and beacon location data when some values are missing then it is ignored`() {
        val ignoredLocationDataEvents = listOf(
                // missing sender id
                IgnoredBeaconLocationDataEvent(
                        event = Event(eventId = AN_EVENT_ID),
                        beaconLocationData = MessageBeaconLocationDataContent()
                ),
                // empty sender id
                IgnoredBeaconLocationDataEvent(
                        event = Event(eventId = AN_EVENT_ID, senderId = ""),
                        beaconLocationData = MessageBeaconLocationDataContent()
                ),
        )

        ignoredLocationDataEvents.forEach {
            val result = liveLocationAggregationProcessor.handleBeaconLocationData(
                    stores = db.stores,
                    event = it.event,
                    content = it.beaconLocationData,
                    roomId = A_ROOM_ID,
                    relatedEventId = "",
                    isLocalEcho = false
            )
            result shouldBeEqualTo false
        }
    }

    @Test
    fun `given beacon location data when relatedEventId is null or empty then it is ignored`() {
        val event = Event(senderId = A_SENDER_ID)
        val beaconLocationData = MessageBeaconLocationDataContent()

        listOf(null, "").forEach {
            val result = liveLocationAggregationProcessor.handleBeaconLocationData(
                    stores = db.stores,
                    event = event,
                    content = beaconLocationData,
                    roomId = A_ROOM_ID,
                    relatedEventId = it,
                    isLocalEcho = false
            )
            result shouldBeEqualTo false
        }
    }

    @Test
    fun `given beacon location data when location is less recent than the saved one then it is ignored`() {
        val event = Event(eventId = AN_EVENT_ID, senderId = A_SENDER_ID)
        val beaconLocationData = MessageBeaconLocationDataContent(
                unstableTimestampMillis = A_TIMESTAMP - 60_000
        )
        val lastBeaconLocationContent = MessageBeaconLocationDataContent(
                unstableTimestampMillis = A_TIMESTAMP
        )
        seedSummary(eventId = AN_EVENT_ID, roomId = A_ROOM_ID, beaconLocationContent = lastBeaconLocationContent)

        val result = liveLocationAggregationProcessor.handleBeaconLocationData(
                stores = db.stores,
                event = event,
                content = beaconLocationData,
                roomId = A_ROOM_ID,
                relatedEventId = AN_EVENT_ID,
                isLocalEcho = false
        )

        result shouldBeEqualTo false
        db.stores.liveLocation.get(AN_EVENT_ID)!!.relatedEventIds shouldContain AN_EVENT_ID
    }

    @Test
    fun `given beacon location data when location is more recent than the saved one then it is aggregated`() {
        val event = Event(eventId = AN_EVENT_ID, senderId = A_SENDER_ID)
        val locationInfo = LocationInfo(geoUri = A_GEO_URI)
        val beaconLocationData = MessageBeaconLocationDataContent(
                unstableTimestampMillis = A_TIMESTAMP,
                unstableLocationInfo = locationInfo
        )
        val lastBeaconLocationContent = MessageBeaconLocationDataContent(
                unstableTimestampMillis = A_TIMESTAMP - 60_000
        )
        seedSummary(eventId = AN_EVENT_ID, roomId = A_ROOM_ID, beaconLocationContent = lastBeaconLocationContent)

        val result = liveLocationAggregationProcessor.handleBeaconLocationData(
                stores = db.stores,
                event = event,
                content = beaconLocationData,
                roomId = A_ROOM_ID,
                relatedEventId = AN_EVENT_ID,
                isLocalEcho = false
        )

        result shouldBeEqualTo true
        val aggregatedEntity = db.stores.liveLocation.get(AN_EVENT_ID)!!
        aggregatedEntity.relatedEventIds shouldContain AN_EVENT_ID
        val savedLocationData = ContentMapper.map(aggregatedEntity.lastLocationContent).toModel<MessageBeaconLocationDataContent>()
        savedLocationData?.getBestTimestampMillis() shouldBeEqualTo A_TIMESTAMP
        savedLocationData?.getBestLocationInfo()?.geoUri shouldBeEqualTo A_GEO_URI
    }

    private fun seedSummary(
            eventId: String,
            roomId: String,
            beaconLocationContent: MessageBeaconLocationDataContent? = null
    ) {
        db.stores.liveLocation.upsert(LiveLocationShareAggregatedSummaryEntity(
                eventId = eventId,
                roomId = roomId,
                lastLocationContent = ContentMapper.map(beaconLocationContent?.toContent())
        ))
    }

    // A previous active beacon from the same user, started earlier than the incoming one, so the SQL
    // filter (non-null start timestamp < current) selects it for deactivation.
    private fun seedPreviousActiveBeacon(eventId: String) {
        db.stores.liveLocation.upsert(LiveLocationShareAggregatedSummaryEntity(
                eventId = eventId,
                roomId = A_ROOM_ID,
                userId = A_SENDER_ID,
                isActive = true,
        ).apply { startOfLiveTimestampMillis = A_TIMESTAMP - 1000 })
    }
}
