/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.platform

import com.squareup.moshi.JsonClass
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.android.sdk.internal.worker.BackgroundTaskBody
import org.matrix.android.sdk.internal.worker.BackgroundTaskContext
import org.matrix.android.sdk.internal.worker.BackgroundTaskOutcome
import org.matrix.android.sdk.internal.worker.SessionWorkerParams
import javax.inject.Provider

@JsonClass(generateAdapter = true)
internal data class TestParams(
        override val sessionId: String = "a-session",
        val value: String = "",
        override val lastFailureMessage: String? = null
) : SessionWorkerParams {

    override fun withFailure(message: String) = copy(lastFailureMessage = lastFailureMessage ?: message)
}

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineBackgroundTaskSchedulerTest {

    private class RecordingBody(
            private val log: MutableList<String>,
            private val name: String,
            private val outcome: (TestParams, Int) -> BackgroundTaskOutcome = { _, _ -> BackgroundTaskOutcome.Success },
    ) : BackgroundTaskBody<TestParams> {

        override suspend fun execute(params: TestParams, context: BackgroundTaskContext): BackgroundTaskOutcome {
            log.add("$name:${params.value}")
            return outcome(params, context.attemptCount)
        }

        override fun onError(params: TestParams, failureMessage: String): BackgroundTaskOutcome {
            log.add("$name:error:$failureMessage")
            return BackgroundTaskOutcome.SuccessWith(params)
        }
    }

    private fun request(type: BackgroundTaskType, value: String, isolateInput: Boolean = false) =
            BackgroundTaskRequest(type, TestParams::class.java, TestParams(value = value), isolateInput = isolateInput)

    private fun scheduler(scope: TestScope, bodies: Map<BackgroundTaskType, BackgroundTaskBody<*>>) =
            CoroutineBackgroundTaskScheduler(scope, bodies.mapValues { entry -> Provider { entry.value } })

    @Test
    fun `runs an enqueued task`() = runTest {
        val log = mutableListOf<String>()
        val scheduler = scheduler(this, mapOf(BackgroundTaskType.SEND_EVENT to RecordingBody(log, "send")))

        scheduler.enqueue(request(BackgroundTaskType.SEND_EVENT, "one"))
        advanceUntilIdle()

        assertEquals(listOf("send:one"), log)
    }

    @Test
    fun `runs a queue in order`() = runTest {
        val log = mutableListOf<String>()
        val scheduler = scheduler(this, mapOf(BackgroundTaskType.SEND_EVENT to RecordingBody(log, "send")))

        scheduler.enqueueUnique("queue", BackgroundQueuePolicy.APPEND_OR_REPLACE, request(BackgroundTaskType.SEND_EVENT, "one"))
        scheduler.enqueueUnique("queue", BackgroundQueuePolicy.APPEND_OR_REPLACE, request(BackgroundTaskType.SEND_EVENT, "two"))
        advanceUntilIdle()

        assertEquals(listOf("send:one", "send:two"), log)
    }

    @Test
    fun `a chain forwards the first task's failure to the second`() = runTest {
        val log = mutableListOf<String>()
        val failing = RecordingBody(log, "upload") { params, _ ->
            BackgroundTaskOutcome.SuccessWith(params.withFailure("upload failed"))
        }
        val scheduler = scheduler(
                this,
                mapOf(
                        BackgroundTaskType.UPLOAD_CONTENT to failing,
                        BackgroundTaskType.MULTIPLE_EVENT_DISPATCHER to RecordingBody(log, "dispatch"),
                )
        )

        scheduler.enqueueUniqueChain(
                "queue",
                BackgroundQueuePolicy.APPEND_OR_REPLACE,
                request(BackgroundTaskType.UPLOAD_CONTENT, "one"),
                request(BackgroundTaskType.MULTIPLE_EVENT_DISPATCHER, "one"),
        )
        advanceUntilIdle()

        assertEquals(listOf("upload:one", "dispatch:error:upload failed"), log)
    }

    @Test
    fun `retries until the body stops asking`() = runTest {
        val log = mutableListOf<String>()
        val flaky = RecordingBody(log, "sync") { _, attempt ->
            if (attempt < 2) BackgroundTaskOutcome.Retry else BackgroundTaskOutcome.Success
        }
        val scheduler = scheduler(this, mapOf(BackgroundTaskType.SYNC to flaky))

        scheduler.enqueue(request(BackgroundTaskType.SYNC, "one"))
        advanceUntilIdle()

        assertEquals(listOf("sync:one", "sync:one", "sync:one"), log)
    }

    @Test
    fun `replacing a queue cancels what it held`() = runTest {
        val log = mutableListOf<String>()
        val scheduler = scheduler(this, mapOf(BackgroundTaskType.SYNC to RecordingBody(log, "sync")))

        scheduler.enqueueUnique(
                "queue",
                BackgroundQueuePolicy.APPEND_OR_REPLACE,
                BackgroundTaskRequest(
                        BackgroundTaskType.SYNC, TestParams::class.java, TestParams(value = "delayed"),
                        initialDelayMillis = 60_000,
                )
        )
        scheduler.enqueueUnique("queue", BackgroundQueuePolicy.REPLACE, request(BackgroundTaskType.SYNC, "now"))
        advanceUntilIdle()

        assertEquals(listOf("sync:now"), log)
    }
}
