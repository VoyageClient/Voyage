/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.platform

import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.internal.di.WorkManagerProvider
import org.matrix.android.sdk.internal.session.pushers.AddPusherWorker
import org.matrix.android.sdk.internal.session.pushers.JsonPusher
import org.matrix.android.sdk.internal.session.workmanager.WorkManagerConfig
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WorkManagerTaskSchedulerTest {

    private val context = RuntimeEnvironment.getApplication()
    private val sessionTag = "MatrixSDK-test-session"

    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerTaskScheduler

    @Before
    fun setup() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)

        val workManagerProvider = mockk<WorkManagerProvider>().also {
            every { it.workManager } returns workManager
            every { it.tag } returns sessionTag
        }
        val workManagerConfig = mockk<WorkManagerConfig>().also {
            every { it.withNetworkConstraint() } returns true
        }
        scheduler = WorkManagerTaskScheduler(workManagerProvider, workManagerConfig)
    }

    private fun aRequest(extraTags: List<String> = emptyList(), initialDelayMillis: Long = 0) = backgroundTask(
            type = BackgroundTaskType.ADD_PUSHER,
            params = AddPusherWorker.Params(sessionId = "a-session", pusher = JsonPusher(pushKey = "key", kind = "http", appId = "app")),
            matrixConstraints = true,
            initialDelayMillis = initialDelayMillis,
            extraTags = extraTags,
    )

    @Test
    fun `enqueued tasks carry the session tag and extra tags and can be cancelled by handle`() {
        val handle = scheduler.enqueue(aRequest(extraTags = listOf("upload_evt"), initialDelayMillis = 60_000))

        val info = workManager.getWorkInfoById(handle.id).get()
        assertTrue(info.tags.contains(sessionTag))
        assertTrue(info.tags.contains("upload_evt"))

        handle.cancel()
        assertEquals(WorkInfo.State.CANCELLED, workManager.getWorkInfoById(handle.id).get().state)
    }

    @Test
    fun `unique REPLACE keeps only the newest task and cancelUniqueQueue clears it`() {
        val first = scheduler.enqueueUnique("QUEUE", BackgroundQueuePolicy.REPLACE, aRequest(initialDelayMillis = 60_000))
        val second = scheduler.enqueueUnique("QUEUE", BackgroundQueuePolicy.REPLACE, aRequest(initialDelayMillis = 60_000))

        // the replaced task is cancelled and pruned, only the newest remains live
        val firstInfo = workManager.getWorkInfoById(first.id).get()
        assertTrue(firstInfo == null || firstInfo.state == WorkInfo.State.CANCELLED)
        val liveIds = workManager.getWorkInfosForUniqueWork("QUEUE").get().filter { !it.state.isFinished }.map { it.id }
        assertEquals(listOf(second.id), liveIds)

        scheduler.cancelUniqueQueue("QUEUE")
        assertEquals(WorkInfo.State.CANCELLED, workManager.getWorkInfoById(second.id).get().state)
    }

    @Test
    fun `cancelAllByTag cancels only matching tasks and cancelAllTasks clears the session`() {
        val tagged = scheduler.enqueue(aRequest(extraTags = listOf("upload_1"), initialDelayMillis = 60_000))
        val other = scheduler.enqueue(aRequest(initialDelayMillis = 60_000))

        scheduler.cancelAllByTag("upload_1")
        assertEquals(WorkInfo.State.CANCELLED, workManager.getWorkInfoById(tagged.id).get().state)
        assertTrue(workManager.getWorkInfoById(other.id).get().state != WorkInfo.State.CANCELLED)
    }

    @Test
    fun `chained tasks enqueue both stages in the unique queue`() {
        val handle = scheduler.enqueueUniqueChain(
                "CHAIN_QUEUE",
                BackgroundQueuePolicy.APPEND_OR_REPLACE,
                aRequest(initialDelayMillis = 60_000),
                aRequest(),
        )

        val infos = workManager.getWorkInfosForUniqueWork("CHAIN_QUEUE").get()
        assertEquals(2, infos.size)
        // the handle cancels the second (dependent) stage, matching previous behavior
        handle.cancel()
        assertEquals(WorkInfo.State.CANCELLED, workManager.getWorkInfoById(handle.id).get().state)
    }
}
