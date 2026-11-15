/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.sliding

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataEvent
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.accountdata.RoomAccountDataTypes
import org.matrix.android.sdk.api.session.room.model.tag.RoomTag
import org.matrix.android.sdk.api.session.sync.RoomSyncScope
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.session.sync.SyncImportState
import org.matrix.android.sdk.internal.session.sync.SyncTask
import org.matrix.android.sdk.internal.session.sync.SyncTokenStore
import org.matrix.android.sdk.internal.settings.DefaultLightweightSettingsStorage

class SlidingSyncTaskTest {

    private val roomId = "!room:example.org"
    private val otherRoomId = "!other:example.org"
    private val subscriptions = SlidingSyncRoomSubscriptions()
    private val stores = mockk<SessionStores>(relaxed = true)
    private val settings = mockk<DefaultLightweightSettingsStorage>()
    private val tokens = mockk<SyncTokenStore>(relaxed = true)
    private val api = mockk<SlidingSyncAPI>()
    private val requests = mutableListOf<SlidingSyncRequest>()
    private var scope = RoomSyncScope.OPENED_ONLY
    private var mode = SlidingSyncMode.SIMPLIFIED
    private var onRequest: () -> Unit = {}
    private var response = SlidingSyncResponse(pos = "next", rooms = mapOf(roomId to messageRoom()))

    private val task: SlidingSyncTask

    init {
        every { settings.getRoomSyncScope(any()) } answers { scope }
        every { tokens.getSlidingSyncStateVersion() } returns SlidingSyncTask.STATE_VERSION
        every { tokens.getSlidingSyncTransport() } answers { mode.name }
        every { tokens.getSlidingSyncPos() } returns "previous"
        every { tokens.getSlidingSyncCoverage() } returns 9
        every { stores.syncToken.getUnsyncedRooms() } returns null
        coEvery { api.simplifiedSlidingSync(any(), any(), capture(requests), any(), any(), any()) } answers {
            onRequest()
            response
        }
        coEvery { api.paginatedSync(any(), any(), capture(requests), any(), any(), any()) } answers {
            onRequest()
            response
        }
        task = SlidingSyncTask(
                slidingSyncAPI = api,
                translator = SlidingSyncTranslator("@me:example.org", stores),
                syncResponseHandler = mockk(relaxed = true),
                syncTokenStore = tokens,
                syncRequestStateTracker = mockk(relaxed = true),
                globalErrorReceiver = mockk(relaxed = true),
                userStore = mockk(relaxed = true),
                session = mockk(relaxed = true),
                clock = mockk(relaxed = true),
                homeServerCapabilitiesDataSource = mockk(relaxed = true),
                sessionDbPriority = mockk(relaxed = true),
                shieldSummaryUpdater = mockk(relaxed = true),
                syncImportState = SyncImportState(),
                roomSubscriptions = subscriptions,
                unsyncedRoomTracker = UnsyncedRoomTracker(stores),
                settingsStorage = settings,
                stores = stores,
                userId = "@me:example.org",
        )
    }

    @Test
    fun `visible unopened rooms receive neither a message subscription nor an imported timeline`() = runBlocking {
        subscriptions.update(setOf(roomId, otherRoomId), emptySet())

        val synced = sync()

        assertNull(requests.single().roomSubscriptions)
        requests.single().lists!!.filterKeys { it != "always" }.values.forEach { assertEquals(0, it.timelineLimit) }
        assertEquals(listOf(RoomTag.ROOM_TAG_SYNC_ALWAYS), requests.single().lists!!["always"]!!.filters!!.tags)
        assertNull(synced.rooms!!.join[roomId]!!.timeline)
        assertFalse(synced.rooms!!.join[roomId]!!.canSyncTimeline)
    }

    @Test
    fun `an open room receives messages and catches up after being closed`() = runBlocking {
        subscriptions.acquire(roomId)
        assertNotNull(sync().rooms!!.join[roomId]!!.timeline)
        assertTrue(requests.last().roomSubscriptions!![roomId]!!.timelineLimit > 0)

        subscriptions.release(roomId)
        assertNull(sync().rooms!!.join[roomId]!!.timeline)
        assertNull(requests.last().roomSubscriptions)

        subscriptions.acquire(roomId)
        assertTrue(sync().rooms!!.join[roomId]!!.timeline!!.limited == true)
    }

    @Test
    fun `a room closed during a request does not import that request's messages`() = runBlocking {
        subscriptions.acquire(roomId)
        onRequest = { subscriptions.release(roomId) }

        assertNull(sync().rooms!!.join[roomId]!!.timeline)
        assertNotNull(requests.single().roomSubscriptions!![roomId])
    }

    @Test
    fun `a scope narrowed during a request does not import its excluded messages`() = runBlocking {
        scope = RoomSyncScope.ALL
        onRequest = { scope = RoomSyncScope.OPENED_ONLY }

        assertNull(sync().rooms!!.join[roomId]!!.timeline)
        assertTrue(requests.single().lists!!["all"]!!.timelineLimit > 0)
    }

    @Test
    fun `direct-only scope accepts a new DM identified in the response`() = runBlocking {
        scope = RoomSyncScope.DIRECT_MESSAGES
        response = response.copy(rooms = mapOf(roomId to messageRoom().copy(isDm = true), otherRoomId to messageRoom()))

        val synced = sync()

        assertNotNull(synced.rooms!!.join[roomId]!!.timeline)
        assertNull(synced.rooms!!.join[otherRoomId]!!.timeline)
        assertTrue(requests.single().lists!!["dms"]!!.timelineLimit > 0)
        assertEquals(0, requests.single().lists!!["all"]!!.timelineLimit)
    }

    @Test
    fun `direct-only scope uses m direct from the same response`() = runBlocking {
        scope = RoomSyncScope.DIRECT_MESSAGES
        response = response.copy(extensions = SlidingSyncExtensionsResponse(accountData = AccountDataExtensionResponse(
                global = listOf(UserAccountDataEvent(UserAccountDataTypes.TYPE_DIRECT_MESSAGES, mapOf("@alice:example.org" to listOf(roomId)))),
        )))

        assertNotNull(sync().rooms!!.join[roomId]!!.timeline)
    }

    @Test
    fun `room overrides from the same response take precedence over the global scope`() = runBlocking {
        response = response.copy(extensions = tags(RoomTag.ROOM_TAG_SYNC_ALWAYS))
        assertNotNull(sync().rooms!!.join[roomId]!!.timeline)

        scope = RoomSyncScope.ALL
        response = response.copy(extensions = tags(RoomTag.ROOM_TAG_SYNC_WHEN_OPEN))
        assertNull(sync().rooms!!.join[roomId]!!.timeline)

        subscriptions.acquire(roomId)
        assertNotNull(sync().rooms!!.join[roomId]!!.timeline)
    }

    @Test
    fun `a paginated fallback still excludes unopened rooms and catches up when opened`() = runBlocking {
        mode = SlidingSyncMode.PAGINATED

        assertNull(sync().rooms!!.join[roomId]!!.timeline)

        subscriptions.acquire(roomId)
        assertTrue(sync().rooms!!.join[roomId]!!.timeline!!.limited == true)
    }

    @Test
    fun `unrestricted sync still imports messages for unopened rooms`() = runBlocking {
        scope = RoomSyncScope.ALL

        assertNotNull(sync().rooms!!.join[roomId]!!.timeline)
        assertFalse(requests.single().lists!!.values.any { it.timelineLimit == 0 })
    }

    private suspend fun sync() = task.sync(mode, SyncTask.Params(0L, null, false))

    private fun tags(tag: String) = SlidingSyncExtensionsResponse(accountData = AccountDataExtensionResponse(
            rooms = mapOf(roomId to listOf(Event(type = RoomAccountDataTypes.EVENT_TYPE_TAG, content = mapOf("tags" to mapOf(tag to emptyMap<String, Any>()))))),
    ))

    private fun messageRoom() = SlidingSyncRoom(
            membership = "join",
            prevBatch = "history",
            timeline = listOf(Event(type = EventType.MESSAGE, eventId = "$" + "a".repeat(43), content = mapOf("body" to "Example message"))),
    )
}
