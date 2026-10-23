/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync

import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.internal.session.sync.sliding.SlidingSyncRequiredState
import org.matrix.android.sdk.internal.session.sync.sliding.SlidingSyncTask

class DefaultSyncServiceHasSyncedTest {

    private val syncTokenStore = mockk<SyncTokenStore>()

    private val service = DefaultSyncService(
            sessionId = "session",
            backgroundTaskScheduler = mockk(relaxed = true),
            syncThreadProvider = mockk(relaxed = true),
            syncTokenStore = syncTokenStore,
            syncRequestStateTracker = mockk(relaxed = true),
            sessionState = mockk(relaxed = true),
            syncStateHolder = mockk(relaxed = true),
    )

    private fun stored(v2Token: String?, slidingPos: String?, slidingVersion: String?) {
        every { syncTokenStore.getLastToken() } returns v2Token
        every { syncTokenStore.getSlidingSyncPos() } returns slidingPos
        every { syncTokenStore.getSlidingSyncStateVersion() } returns slidingVersion
    }

    @Test
    fun `a sliding sync pos stored under the current connection version counts as synced`() {
        stored(v2Token = null, slidingPos = "pos", slidingVersion = SlidingSyncTask.STATE_VERSION)
        service.hasAlreadySynced() shouldBeEqualTo true
    }

    @Test
    fun `a sliding sync pos from an outdated connection does not count`() {
        stored(v2Token = null, slidingPos = "pos", slidingVersion = SlidingSyncRequiredState.VERSION)
        service.hasAlreadySynced() shouldBeEqualTo false
    }

    @Test
    fun `nothing stored means not synced, a v2 token means synced`() {
        stored(v2Token = null, slidingPos = null, slidingVersion = null)
        service.hasAlreadySynced() shouldBeEqualTo false
        stored(v2Token = "s1", slidingPos = null, slidingVersion = null)
        service.hasAlreadySynced() shouldBeEqualTo true
    }
}
