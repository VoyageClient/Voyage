/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.bulk

import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.features.redaction.MassRedactionManager
import im.vector.app.features.redaction.MassRedactionState
import im.vector.app.test.fakes.FakeActiveSessionDataSource
import im.vector.app.test.fakes.FakeStringProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.room.Room
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BulkActionQueueTest {

    private val massState = MutableStateFlow<MassRedactionState?>(null)
    private val massRedactionManager = mockk<MassRedactionManager>(relaxed = true) {
        every { stream() } returns massState
        every { currentValue() } answers { massState.value }
    }
    private val room = mockk<Room>(relaxed = true)
    private val session = mockk<Session>(relaxed = true) {
        every { myUserId } returns ME
        every { roomService().getRoom(ROOM) } returns room
    }
    private val sent: MutableList<Pair<Content, String>> = Collections.synchronizedList(mutableListOf())

    private fun newQueue(): BulkActionQueue {
        val dataSource = FakeActiveSessionDataSource()
        val queue = BulkActionQueue(
                context = RuntimeEnvironment.getApplication(),
                activeSessionHolder = mockk<ActiveSessionHolder> { every { getSafeActiveSession() } returns session },
                activeSessionDataSource = dataSource.instance,
                massRedactionManager = massRedactionManager,
                popupAlertManager = mockk(relaxed = true),
                stringProvider = FakeStringProvider().instance,
        )
        dataSource.setActiveSession(session)
        waitUntil { queueLoaded(queue) }
        return queue
    }

    // The session arrives on a background coroutine; a job enqueued before it is dropped.
    private fun queueLoaded(queue: BulkActionQueue) = BulkActionQueue::class.java.getDeclaredField("owner")
            .apply { isAccessible = true }.get(queue) == ME

    @After
    fun tearDown() {
        File(RuntimeEnvironment.getApplication().filesDir, "bulk_queue").deleteRecursively()
    }

    @Test
    fun `forwards are sent in order and an in-batch reply points at the earlier copy`() {
        coEvery { room.sendService().sendEventNow(any(), any(), any()) } answers {
            val content = secondArg<Content>()
            sent += content to thirdArg()
            "\$sent-${content["body"]}"
        }
        val queue = newQueue()

        queue.enqueueForward(listOf(ROOM), listOf(text("one"), text("two"), text("three", replyTo = 0)))

        waitUntil { sent.size == 3 }
        sent.map { it.first["body"] } shouldBeEqualTo listOf("one", "two", "three")
        sent[2].first["m.relates_to"] shouldBeEqualTo mapOf("m.in_reply_to" to mapOf("event_id" to "\$sent-one"))
        sent[0].first.containsKey("m.relates_to") shouldBeEqualTo false
    }

    @Test
    fun `a failed forward pauses the job and resuming sends it again with the same transaction`() {
        var failing = true
        coEvery { room.sendService().sendEventNow(any(), any(), any()) } answers {
            val content = secondArg<Content>()
            if (content["body"] == "two" && failing) throw IllegalStateException("rejected")
            sent += content to thirdArg()
            "\$sent-${content["body"]}"
        }
        val queue = newQueue()
        queue.enqueueForward(listOf(ROOM), listOf(text("one"), text("two"), text("three")))

        // Three attempts a few seconds apart, then the job pauses on "two" rather than skipping it.
        runBlocking { withTimeout(20_000) { queue.progress.first { it?.paused == true } } }
        sent.map { it.first["body"] } shouldBeEqualTo listOf("one")

        failing = false
        queue.togglePause()

        waitUntil { sent.size == 3 }
        sent.map { it.first["body"] } shouldBeEqualTo listOf("one", "two", "three")
        coVerify(atLeast = 2) { room.sendService().sendEventNow(any(), match { it["body"] == "two" }, sent[1].second) }
    }

    @Test
    fun `a delete redacts each message and its own reactions`() {
        val reaction = Event(eventId = "\$reaction", senderId = ME)
        val othersReaction = Event(eventId = "\$theirs", senderId = "@other:example.org")
        coEvery { room.relationService().fetchReactions("\$a") } returns listOf(reaction, othersReaction)
        coEvery { room.relationService().fetchEditHistory(any()) } returns emptyList()
        every { room.stateService().getRoomPowerLevels().isUserAbleToRedact(ME) } returns false
        val redacted: MutableList<String> = Collections.synchronizedList(mutableListOf())
        coEvery { room.relationService().redactEventNoEcho(any(), any()) } answers { redacted += firstArg<String>() }
        val queue = newQueue()

        queue.enqueueDelete(ROOM, listOf("\$a", "\$b"), reason = null)

        waitUntil { redacted.size == 3 }
        redacted.toList() shouldBeEqualTo listOf("\$a", "\$reaction", "\$b")
    }

    @Test
    fun `a job waits behind a running mass redaction`() {
        coEvery { room.sendService().sendEventNow(any(), any(), any()) } answers { sent += secondArg<Content>() to thirdArg(); "\$id" }
        massState.value = MassRedactionState(ROOM, "@spammer:example.org", "Spammer", completed = 0, total = 10, paused = false)
        val queue = newQueue()

        queue.enqueueForward(listOf(ROOM), listOf(text("one"), text("two")))
        Thread.sleep(500)
        sent.size shouldBeEqualTo 0

        massState.value = null
        waitUntil { sent.size == 2 }
    }

    private fun text(body: String, replyTo: Int? = null): Triple<String, Content, Int?> =
            Triple("m.room.message", mapOf("msgtype" to "m.text", "body" to body), replyTo)

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }

    companion object {
        private const val ME = "@me:example.org"
        private const val ROOM = "!room:example.org"
    }
}
