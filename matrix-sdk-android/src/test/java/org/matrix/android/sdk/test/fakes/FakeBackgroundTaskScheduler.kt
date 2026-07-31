/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.test.fakes

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.matrix.android.sdk.internal.platform.BackgroundQueuePolicy
import org.matrix.android.sdk.internal.platform.BackgroundTaskHandle
import org.matrix.android.sdk.internal.platform.BackgroundTaskScheduler
import java.util.UUID

internal class FakeBackgroundTaskScheduler {

    val instance = mockk<BackgroundTaskScheduler>().also {
        every { it.enqueue(any()) } returns newHandle()
        every { it.enqueueUnique(any(), any(), any()) } returns newHandle()
        every { it.enqueueUniqueChain(any(), any(), any(), any()) } returns newHandle()
        justRun { it.cancelUniqueQueue(any()) }
        justRun { it.cancelAllByTag(any()) }
        justRun { it.cancelAllTasks() }
    }

    fun verifyEnqueueUnique(queueName: String, policy: BackgroundQueuePolicy) {
        verify { instance.enqueueUnique(queueName, policy, any()) }
    }

    fun verifyCancelUniqueQueue(queueName: String) {
        verify { instance.cancelUniqueQueue(queueName) }
    }

    private fun newHandle() = BackgroundTaskHandle(UUID.randomUUID()) {}
}
