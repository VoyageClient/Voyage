/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send.queue

import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.internal.task.TaskExecutor
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EventSenderProcessorOrderTest {

    private val processor = EventSenderProcessorCoroutine(
            cryptoStore = mockk(relaxed = true),
            homeServerFallbackTracker = mockk(relaxed = true),
            queuedTaskFactory = mockk(),
            taskExecutor = TaskExecutor(mockk(relaxed = true)),
            localEchoRepository = mockk(relaxed = true),
            memento = mockk(relaxed = true),
    )

    private val executed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val failed: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private inner class FakeTask(
            val id: String,
            private val done: CountDownLatch,
            private val failures: MutableList<Throwable> = mutableListOf(),
    ) : QueuedTask(queueIdentifier = "!room", taskIdentifier = id) {
        override suspend fun doExecute() {
            failures.removeFirstOrNull()?.let { throw it }
            executed.add(id)
            done.countDown()
        }

        override fun onTaskFailed() {
            failed.add(id)
            done.countDown()
        }
    }

    @Test
    fun `tasks posted back to back run in post order`() {
        val done = CountDownLatch(50)
        val ids = (0 until 50).map { "\$$it" }
        ids.forEach { processor.postTask(FakeTask(it, done)) }

        done.await(10, TimeUnit.SECONDS) shouldBeEqualTo true
        executed.toList() shouldBeEqualTo ids
    }

    @Test
    fun `a rate limit does not use up the regular retries`() {
        val done = CountDownLatch(1)
        val limited = (1..5).map { rateLimited() }.toMutableList<Throwable>()
        processor.postTask(FakeTask("\$1", done, limited))

        done.await(10, TimeUnit.SECONDS) shouldBeEqualTo true
        executed.toList() shouldBeEqualTo listOf("\$1")
        failed.toList() shouldBeEqualTo emptyList()
    }

    private fun rateLimited() = Failure.ServerError(
            MatrixError(code = MatrixError.M_LIMIT_EXCEEDED, message = "slow down", retryAfterMillis = 1),
            httpCode = 429,
    )
}
