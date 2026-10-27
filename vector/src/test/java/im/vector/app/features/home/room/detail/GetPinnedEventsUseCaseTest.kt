/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail

import im.vector.app.features.home.room.detail.pinned.GetPinnedEventsUseCase
import im.vector.app.features.home.room.detail.timeline.helper.SenderProfileResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.model.OlmDecryptionResult
import org.matrix.android.sdk.api.session.events.EventService
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.state.StateService
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.TimelineService
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional

@OptIn(ExperimentalCoroutinesApi::class)
class GetPinnedEventsUseCaseTest {
    private val roomId = "!room:example.org"
    private val room = mockk<Room>()
    private val session = mockk<Session>()
    private val stateService = mockk<StateService>()
    private val timelineService = mockk<TimelineService>()
    private val eventService = mockk<EventService>(relaxed = true)
    private val profiles = mockk<SenderProfileResolver>()
    private val state = MutableStateFlow<Optional<Event>>(null.toOptional())
    private val cached = mutableMapOf<String, TimelineEvent>()
    private val rows = mutableMapOf<String, MutableStateFlow<Optional<TimelineEvent>>>()
    private val decryption = MutableSharedFlow<Unit>()

    private fun row(id: String) = rows.getOrPut(id) { MutableStateFlow(cached[id].toOptional()) }

    private fun pin(vararg ids: String) {
        state.value = Event(
                type = EventType.STATE_ROOM_PINNED_EVENT,
                eventId = "\$pins",
                roomId = roomId,
                stateKey = "",
                content = mapOf("pinned" to ids.toList()),
        ).toOptional()
    }

    private fun event(id: String, ts: Long = 1, encrypted: Boolean = false) = TimelineEvent(
            root = Event(
                    eventId = id,
                    roomId = roomId,
                    type = if (encrypted) EventType.ENCRYPTED else EventType.MESSAGE,
                    originServerTs = ts,
                    content = mapOf("body" to id),
            ),
            localId = ts,
            eventId = id,
            senderInfo = SenderInfo("@alice:example.org", null, true, null),
    )

    private fun cache(event: TimelineEvent) {
        cached[event.eventId] = event
        row(event.eventId).value = event.toOptional()
    }

    private fun TestScope.observe(): MutableList<List<TimelineEvent>> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        every { room.roomId } returns roomId
        every { room.coroutineDispatchers } returns MatrixCoroutineDispatchers(dispatcher, dispatcher, dispatcher, dispatcher, dispatcher)
        every { room.stateService() } returns stateService
        every { room.timelineService() } returns timelineService
        every { session.eventService() } returns eventService
        every { stateService.getStateEvent(any(), any()) } answers { state.value.getOrNull() }
        every { stateService.getStateEventFlow(any(), any()) } returns state
        every { timelineService.getTimelineEvent(any()) } answers { cached[firstArg()] }
        every { timelineService.getTimelineEventFlow(any()) } answers { row(firstArg()) }
        every { eventService.decryptionUpdates(roomId) } returns decryption
        every { profiles.resolve(any(), any(), any()) } answers { secondArg() }
        val results = mutableListOf<List<TimelineEvent>>()
        backgroundScope.launch { GetPinnedEventsUseCase(session, profiles).execute(room).collect { results.add(it) } }
        return results
    }

    @Test
    fun `cached pins display while another pin is still fetching`() = runTest {
        pin("slow", "newer", "older")
        cache(event("newer", ts = 2))
        cache(event("older"))
        coEvery { eventService.ensureEventCached(roomId, "slow", true) } coAnswers { awaitCancellation() }

        val results = observe()
        runCurrent()

        assertEquals(listOf("older", "newer"), results.last().map { it.eventId })
        coVerify(exactly = 1) { eventService.ensureEventCached(roomId, "slow", true) }
        coVerify(exactly = 0) { eventService.ensureEventCached(roomId, "older", true) }
    }

    @Test
    fun `a slow first fetch does not hold back a later pin`() = runTest {
        pin("slow", "fast")
        coEvery { eventService.ensureEventCached(roomId, "slow", true) } coAnswers { awaitCancellation() }
        coEvery { eventService.ensureEventCached(roomId, "fast", true) } coAnswers {
            event("fast").also { cache(it) }.root
        }

        val results = observe()
        runCurrent()

        assertEquals(listOf("fast"), results.last().map { it.eventId })
    }

    @Test
    fun `initial sync state and a later cached message appear without reopening the room`() = runTest {
        coEvery { eventService.ensureEventCached(roomId, "late", true) } returns null
        val results = observe()
        runCurrent()
        assertTrue(results.last().isEmpty())

        pin("late")
        runCurrent()
        assertTrue(results.last().isEmpty())
        coVerify(exactly = 1) { eventService.ensureEventCached(roomId, "late", true) }

        cache(event("late"))
        runCurrent()
        assertEquals(listOf("late"), results.last().map { it.eventId })
    }

    @Test
    fun `a failed fetch leaves cache observation active`() = runTest {
        pin("failed", "cached")
        cache(event("cached"))
        coEvery { eventService.ensureEventCached(roomId, "failed", true) } throws IllegalStateException("offline")
        val results = observe()
        runCurrent()
        assertEquals(listOf("cached"), results.last().map { it.eventId })

        cache(event("failed", ts = 2))
        runCurrent()
        assertEquals(listOf("cached", "failed"), results.last().map { it.eventId })
    }

    @Test
    fun `fetch concurrency is bounded and queued pins are cancelled on unpin`() = runTest {
        pin(*(1..10).map { "pin$it" }.toTypedArray())
        coEvery { eventService.ensureEventCached(roomId, any(), true) } coAnswers { awaitCancellation() }
        val results = observe()
        runCurrent()
        coVerify(exactly = 4) { eventService.ensureEventCached(roomId, any(), true) }

        pin()
        runCurrent()
        coVerify(exactly = 4) { eventService.ensureEventCached(roomId, any(), true) }
        assertTrue(results.last().isEmpty())
    }

    @Test
    fun `decryption is observed during fetching without refetching missing pins`() = runTest {
        pin("encrypted", "missing")
        cache(event("encrypted", encrypted = true))
        val fetch = CompletableDeferred<Event?>()
        coEvery { eventService.ensureEventCached(roomId, "missing", true) } coAnswers { fetch.await() }
        val results = observe()
        runCurrent()
        assertEquals(EventType.ENCRYPTED, results.last().single().root.getClearType())

        cached["encrypted"] = event("encrypted", encrypted = true).also {
            it.root.mxDecryptionResult = OlmDecryptionResult(
                    payload = mapOf("type" to EventType.MESSAGE, "content" to mapOf("body" to "decrypted")),
            )
        }
        decryption.emit(Unit)
        runCurrent()
        assertEquals("decrypted", results.last().single().root.getClearContent()?.get("body"))
        assertFalse(fetch.isCompleted)

        fetch.complete(null)
        runCurrent()
        decryption.emit(Unit)
        runCurrent()
        coVerify(exactly = 1) { eventService.ensureEventCached(roomId, "missing", true) }
        verify(exactly = 1) { eventService.requestDecryption(any()) }
    }

    @Test
    fun `unpinning cancels pending fetches and removes displayed pins`() = runTest {
        pin("cached", "missing")
        cache(event("cached"))
        var cancelled = false
        coEvery { eventService.ensureEventCached(roomId, "missing", true) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val results = observe()
        runCurrent()

        pin()
        runCurrent()

        assertTrue(cancelled)
        assertTrue(results.last().isEmpty())
        cache(event("missing"))
        runCurrent()
        assertTrue(results.last().isEmpty())
    }

    @Test
    fun `evicting a timeline row does not hide a loaded pin`() = runTest {
        pin("cached")
        cache(event("cached"))
        val results = observe()
        runCurrent()

        cached.remove("cached")
        row("cached").value = null.toOptional()
        runCurrent()

        assertEquals(listOf("cached"), results.last().map { it.eventId })
        assertEquals(1, results.size)
    }
}
