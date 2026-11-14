/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.membership

import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.internal.database.model.RoomEntity
import org.matrix.android.sdk.internal.database.model.RoomMembersLoadStatusType
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.session.sync.SyncTokenStore
import org.matrix.android.sdk.test.fakes.FakeClock
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LoadRoomMembersTaskTest {
    private val db = FakeSessionDatabase()
    private val roomId = "!room:example.org"
    private val roomAPI = mockk<RoomAPI>()
    private val memberHandler = mockk<SqlRoomMemberEventHandler>(relaxed = true)
    private val tokens = SyncTokenStore(db.stores)
    private val task = DefaultLoadRoomMembersTask(
            roomAPI = roomAPI,
            database = db.database,
            dispatcher = db.dispatcher,
            stores = db.stores,
            coroutineDispatchers = MatrixCoroutineDispatchers(
                    Dispatchers.Default, Dispatchers.Default, Dispatchers.Default, Dispatchers.Default, Dispatchers.Default
            ),
            syncTokenStore = tokens,
            roomSummaryUpdater = mockk(relaxed = true),
            roomMemberEventHandler = memberHandler,
            cryptoSessionInfoProvider = mockk { every { isRoomEncrypted(any()) } returns false },
            cryptoService = Lazy { mockk<CryptoService>(relaxed = true) },
            globalErrorReceiver = mockk(relaxed = true),
            clock = FakeClock().apply { givenEpoch(1000L) },
    )

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun initialize(sliding: Boolean = true) = withContext(db.dispatcher) {
        db.stores.room.upsert(RoomEntity(roomId = roomId).apply { membership = Membership.JOIN })
        if (sliding) tokens.setSlidingSyncPos("position")
    }

    private fun event(id: String, stateKey: String = "@alice:example.org") = Event(
            type = EventType.STATE_ROOM_MEMBER,
            eventId = id,
            stateKey = stateKey,
            senderId = stateKey,
            content = mapOf("membership" to "join", "displayname" to "Alice"),
    )

    @Test
    fun `concurrent full state callers share a single request`() = runBlocking<Unit> {
        initialize()
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<List<Event>>()
        coEvery { roomAPI.getRoomState(roomId) } coAnswers {
            entered.complete(Unit)
            response.await()
        }
        val first = async { task.execute(LoadRoomMembersTask.Params(roomId, fullState = true)) }
        entered.await()
        val others = (1..10).map { async { task.execute(LoadRoomMembersTask.Params(roomId, fullState = true)) } }
        response.complete(listOf(event("\$member")))
        (others + first).awaitAll()

        coVerify(exactly = 1) { roomAPI.getRoomState(roomId) }
        withContext(db.dispatcher) { db.stores.room.get(roomId)?.membersLoadStatus } shouldBeEqualTo RoomMembersLoadStatusType.LOADED
    }

    @Test
    fun `full state requests on classic sync stop after members are loaded`() = runBlocking<Unit> {
        initialize(sliding = false)
        coEvery { roomAPI.getMembers(roomId, any(), any(), any()) } returns RoomMembersResponse(emptyList())

        repeat(3) { task.execute(LoadRoomMembersTask.Params(roomId, fullState = true)) }

        coVerify(exactly = 1) { roomAPI.getMembers(roomId, any(), any(), any()) }
        coVerify(exactly = 0) { roomAPI.getRoomState(any()) }
    }

    @Test
    fun `members only load can be followed by one full state load`() = runBlocking<Unit> {
        initialize()
        coEvery { roomAPI.getMembers(roomId, any(), any(), any()) } returns RoomMembersResponse(listOf(event("\$member")))
        coEvery { roomAPI.getRoomState(roomId) } returns listOf(event("\$member"))

        task.execute(LoadRoomMembersTask.Params(roomId))
        repeat(3) { task.execute(LoadRoomMembersTask.Params(roomId, fullState = true)) }

        coVerify(exactly = 1) { roomAPI.getMembers(roomId, any(), any(), any()) }
        coVerify(exactly = 1) { roomAPI.getRoomState(roomId) }
        verify(exactly = 1) { memberHandler.handle(any(), roomId, any(), false, any()) }
    }

    @Test
    fun `sync state received during a download is preserved`() = runBlocking<Unit> {
        initialize()
        coEvery { roomAPI.getRoomState(roomId) } coAnswers {
            withContext(db.dispatcher) {
                db.stores.currentStateEvent.upsert(roomId, EventType.STATE_ROOM_MEMBER, "@alice:example.org", "\$new", "\$new")
            }
            listOf(event("\$old"))
        }

        task.execute(LoadRoomMembersTask.Params(roomId, fullState = true))

        withContext(db.dispatcher) {
            db.database.currentStateEventQueries.selectOne(roomId, EventType.STATE_ROOM_MEMBER, "@alice:example.org")
                    .executeAsOneOrNull()?.event_id
        } shouldBeEqualTo "\$new"
        verify(exactly = 0) { memberHandler.handle(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a cancelled download resets loading and allows the next caller to finish`() = runBlocking<Unit> {
        initialize()
        val entered = CompletableDeferred<Unit>()
        coEvery { roomAPI.getRoomState(roomId) } coAnswers {
            entered.complete(Unit)
            CompletableDeferred<List<Event>>().await()
        }
        val request = launch { task.execute(LoadRoomMembersTask.Params(roomId, fullState = true)) }
        entered.await()
        request.cancelAndJoin()
        withContext(db.dispatcher) { db.stores.room.get(roomId)?.membersLoadStatus } shouldBeEqualTo RoomMembersLoadStatusType.NONE
        coEvery { roomAPI.getRoomState(roomId) } returns emptyList()

        task.execute(LoadRoomMembersTask.Params(roomId, fullState = true))

        withContext(db.dispatcher) { db.stores.room.get(roomId)?.membersLoadStatus } shouldBeEqualTo RoomMembersLoadStatusType.LOADED
    }

    @Test
    fun `removed rooms do not retry forbidden full state loads`() = runBlocking<Unit> {
        initialize()
        withContext(db.dispatcher) {
            db.stores.room.upsert(RoomEntity(roomId = roomId).apply { membership = Membership.LEAVE })
        }
        coEvery { roomAPI.getRoomState(roomId) } throws Failure.ServerError(MatrixError(MatrixError.M_FORBIDDEN, "Refused"), 403)

        repeat(3) { task.execute(LoadRoomMembersTask.Params(roomId, fullState = true)) }

        coVerify(exactly = 1) { roomAPI.getRoomState(roomId) }
        withContext(db.dispatcher) { db.stores.room.get(roomId)?.membersLoadStatus } shouldBeEqualTo RoomMembersLoadStatusType.LOADED
    }

    @Test
    fun `rejoining through a members only load still fetches fresh full state`() = runBlocking<Unit> {
        initialize()
        withContext(db.dispatcher) {
            db.stores.room.upsert(RoomEntity(roomId = roomId).apply { membership = Membership.LEAVE })
        }
        coEvery { roomAPI.getRoomState(roomId) } throws Failure.ServerError(MatrixError(MatrixError.M_FORBIDDEN, "Refused"), 403)
        task.execute(LoadRoomMembersTask.Params(roomId, fullState = true))

        initialize()
        coEvery { roomAPI.getMembers(roomId, any(), any(), any()) } returns RoomMembersResponse(emptyList())
        coEvery { roomAPI.getRoomState(roomId) } returns emptyList()
        task.execute(LoadRoomMembersTask.Params(roomId))
        task.execute(LoadRoomMembersTask.Params(roomId, fullState = true))

        coVerify(exactly = 2) { roomAPI.getRoomState(roomId) }
    }

    @Test
    fun `large member responses commit in small batches`() = runBlocking<Unit> {
        initialize()
        val events = (1..105).map { event("\$member-$it", "@member-$it:example.org") }
        coEvery { roomAPI.getRoomState(roomId) } returns events
        val batchSizes = mutableListOf<Int>()
        var previousCount = 0
        val query = db.database.currentStateEventQueries.selectStateIdsByRoom(roomId)
        val listener = app.cash.sqldelight.Query.Listener {
            val count = query.executeAsList().size
            batchSizes.add(count - previousCount)
            previousCount = count
        }
        query.addListener(listener)
        try {
            task.execute(LoadRoomMembersTask.Params(roomId, fullState = true))
        } finally {
            query.removeListener(listener)
        }

        batchSizes.sum() shouldBeEqualTo 105
        batchSizes.all { it in 1..20 } shouldBeEqualTo true
        (batchSizes.size >= 6) shouldBeEqualTo true
    }
}
