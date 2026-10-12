/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.share

import com.airbnb.mvrx.test.MavericksTestRule
import im.vector.app.test.testCoroutineDispatchers
import im.vector.app.test.testDispatcher
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.model.message.ForwardedInfo
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IncomingShareForwardTest {
    @get:Rule
    val mavericksTestRule = MavericksTestRule(testDispatcher = testDispatcher)

    @Test
    fun `picker remains open until audio forward is queued`() = runBlocking {
        val sendEntered = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val room = mockk<Room>(relaxed = true)
        every { room.sendService().sendEvent(any(), any(), any()) } answers {
            sendEntered.countDown()
            check(releaseSend.await(5, TimeUnit.SECONDS))
            mockk(relaxed = true)
        }
        val session = mockk<Session>(relaxed = true) {
            every { coroutineDispatchers } returns testCoroutineDispatchers
            every { roomService().getRoomSummariesFlow(any(), any()) } returns emptyFlow()
            every { roomService().getRoomSummaries(any(), any()) } returns emptyList()
            every { roomService().getRoom("!target:example.org") } returns room
        }
        val payloadId = ForwardPayloadHolder.put(mapOf(ForwardedInfo.STABLE_KEY to mapOf(
                "content" to mapOf("msgtype" to "m.audio", "body" to "Song", "url" to "mxc://example.org/song"),
        )))
        val viewModel = IncomingShareViewModel(
                IncomingShareViewState(
                        sharedData = SharedData.Forward(EventType.MESSAGE, payloadId),
                        selectedRoomIds = setOf("!target:example.org"),
                ), session, mockk(relaxed = true), mockk(relaxed = true),
        )
        val events = Channel<IncomingShareViewEvents>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            viewModel.viewEvents.stream("forward-test").collect { events.send(it) }
        }
        try {
            viewModel.handle(IncomingShareAction.ShareToSelectedRooms())
            assertTrue(sendEntered.await(5, TimeUnit.SECONDS))
            assertTrue(events.tryReceive().isFailure)
            releaseSend.countDown()
            assertEquals(IncomingShareViewEvents.ForwardDone("!target:example.org"), withTimeout(5_000) { events.receive() })
        } finally {
            releaseSend.countDown()
            collector.cancel()
            ForwardPayloadHolder.take(payloadId)
        }
    }
}
