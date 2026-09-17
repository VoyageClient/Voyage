/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.handler

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.internal.crypto.crosssigning.UpdateTrustWorkerDataRepository
import org.matrix.android.sdk.internal.platform.BackgroundQueuePolicy
import org.matrix.android.sdk.internal.platform.BackgroundTaskHandle
import org.matrix.android.sdk.internal.platform.BackgroundTaskRequest
import org.matrix.android.sdk.internal.platform.BackgroundTaskScheduler

class ShieldSummaryUpdaterTest {

    private val enqueuedRoomIds = mutableListOf<List<String>>()

    private val repository = mockk<UpdateTrustWorkerDataRepository>().also {
        val rooms = slot<List<String>>()
        every { it.createParam(any(), capture(rooms)) } answers {
            enqueuedRoomIds.add(rooms.captured)
            "params.json"
        }
    }

    private val scheduler = mockk<BackgroundTaskScheduler>().also {
        every { it.enqueueUnique(any(), any<BackgroundQueuePolicy>(), any<BackgroundTaskRequest<*>>()) } returns mockk<BackgroundTaskHandle>()
    }

    private val updater = ShieldSummaryUpdater("session", scheduler, repository)

    @Test
    fun `a refresh runs straight away when nothing is held`() {
        updater.refreshShieldsForRoomIds(setOf("!a:hs"))

        enqueuedRoomIds shouldBeEqualTo listOf(listOf("!a:hs"))
    }

    @Test
    fun `while held nothing is enqueued`() {
        updater.holdRefreshes()

        updater.refreshShieldsForRoomIds(setOf("!a:hs"))
        updater.refreshShieldsForRoomIds(setOf("!b:hs"))

        enqueuedRoomIds shouldBeEqualTo emptyList()
    }

    @Test
    fun `releasing enqueues every held room exactly once`() {
        updater.holdRefreshes()
        updater.refreshShieldsForRoomIds(setOf("!a:hs", "!b:hs"))
        updater.refreshShieldsForRoomIds(setOf("!b:hs", "!c:hs"))

        updater.releaseRefreshes()

        enqueuedRoomIds shouldBeEqualTo listOf(listOf("!a:hs", "!b:hs", "!c:hs"))
    }

    @Test
    fun `releasing with nothing held does not enqueue an empty pass`() {
        updater.holdRefreshes()

        updater.releaseRefreshes()

        enqueuedRoomIds shouldBeEqualTo emptyList()
    }

    @Test
    fun `a refresh after release goes through again`() {
        updater.holdRefreshes()
        updater.refreshShieldsForRoomIds(setOf("!a:hs"))
        updater.releaseRefreshes()
        enqueuedRoomIds.clear()

        updater.refreshShieldsForRoomIds(setOf("!b:hs"))

        enqueuedRoomIds shouldBeEqualTo listOf(listOf("!b:hs"))
    }

    /** A second release must not replay what the first one already handed over. */
    @Test
    fun `releasing twice does not enqueue the same rooms again`() {
        updater.holdRefreshes()
        updater.refreshShieldsForRoomIds(setOf("!a:hs"))
        updater.releaseRefreshes()

        updater.releaseRefreshes()

        enqueuedRoomIds shouldBeEqualTo listOf(listOf("!a:hs"))
    }
}
