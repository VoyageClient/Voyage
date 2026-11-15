/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import android.os.Looper
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.session.content.ContentUploadStateTracker
import org.matrix.android.sdk.api.session.content.ContentUploadStateTracker.State
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ContentUploadStateTrackerTest {
    private val tracker = DefaultContentUploadStateTracker(MatrixCoroutineDispatchers(
            Dispatchers.IO, Dispatchers.Default, Dispatchers.Main, Dispatchers.Default, Dispatchers.Default,
    ))
    private val states = mutableListOf<State>()
    private val listener = object : ContentUploadStateTracker.UpdateListener {
        override fun onUpdate(state: State) {
            states.add(state)
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun track() {
        tracker.track(KEY, listener)
        idle()
    }

    @Test
    fun `a retry keeps the bar at the furthest position until it catches up`() {
        track()
        tracker.setProgress(KEY, 24_576, 65_536)
        idle()
        tracker.setUploadRetrying(KEY)
        idle()
        assertTrue((states.last() as State.Uploading).isRetrying)
        tracker.setUploadStarted(KEY, true)
        tracker.setProgress(KEY, 8_192, 65_536)
        idle()
        assertEquals(State.Uploading(24_576, 65_536, isRetrying = true), states.last())
        tracker.setProgress(KEY, 32_768, 65_536)
        idle()
        assertEquals(State.Uploading(32_768, 65_536), states.last())
    }

    @Test
    fun `a counter regression is recognized even without an explicit retry callback`() {
        track()
        tracker.setProgress(KEY, 24_576, 65_536)
        tracker.setProgress(KEY, 8_192, 65_536)
        idle()
        assertEquals(State.Uploading(24_576, 65_536, isRetrying = true), states.last())
    }

    @Test
    fun `thumbnail retries have their own byte counter`() {
        track()
        tracker.setProgress(KEY, 65_536, 65_536)
        tracker.setProgressThumbnail(KEY, 16_384, 32_768)
        tracker.setUploadStarted(KEY, true, thumbnail = true)
        tracker.setProgressThumbnail(KEY, 8_192, 32_768)
        idle()
        assertEquals(State.UploadingThumbnail(16_384, 32_768, isRetrying = true), states.last())
    }

    @Test
    fun `writing all bytes waits for a server response before success`() {
        track()
        tracker.setProgress(KEY, 65_536, 65_536)
        tracker.setUploadAwaitingResponse(KEY)
        idle()
        assertEquals(State.Uploading(65_536, 65_536, isAwaitingResponse = true), states.last())
        tracker.setSuccess(KEY)
        idle()
        assertEquals(State.Success, states.last())
    }

    @Test
    fun `a queued initial callback is cancelled by untracking`() {
        tracker.setProgress(KEY, 8_192, 65_536)
        tracker.track(KEY, listener)
        tracker.untrack(KEY, listener)
        tracker.setProgress(KEY, 32_768, 65_536)
        idle()
        assertTrue(states.isEmpty())
    }

    @Test
    fun `a replacement listener receives only the newest queued state`() {
        tracker.track(KEY, listener)
        tracker.setProgress(KEY, 8_192, 65_536)
        tracker.untrack(KEY, listener)
        val replacement = mutableListOf<State>()
        tracker.track(KEY, object : ContentUploadStateTracker.UpdateListener {
            override fun onUpdate(state: State) {
                replacement.add(state)
            }
        })
        tracker.setProgress(KEY, 32_768, 65_536)
        idle()
        assertTrue(states.isEmpty())
        assertEquals(listOf(State.Uploading(32_768, 65_536)), replacement)
    }

    @Test
    fun `leaving and reopening a timeline retains progress across a worker retry`() {
        track()
        tracker.setProgress(KEY, 24_576, 65_536)
        idle()
        tracker.untrack(KEY, listener)
        tracker.setUploadRetrying(KEY)
        tracker.setUploadStarted(KEY, true)
        tracker.setProgress(KEY, 8_192, 65_536)
        states.clear()
        track()
        assertEquals(listOf(State.Uploading(24_576, 65_536, isRetrying = true)), states)
    }

    @Test
    fun `clear invalidates queued callbacks even when the same key is tracked again`() {
        tracker.track(KEY, listener)
        tracker.setProgress(KEY, 8_192, 65_536)
        tracker.clear()
        tracker.track(KEY, listener)
        idle()
        assertEquals(listOf(State.Idle), states)
    }

    @Test
    fun `a listener can untrack another listener during notification`() {
        tracker.track(KEY, object : ContentUploadStateTracker.UpdateListener {
            override fun onUpdate(state: State) {
                tracker.untrack(KEY, listener)
            }
        })
        tracker.track(KEY, listener)
        idle()
        assertTrue(states.isEmpty())
    }

    @Test
    fun `a listener changing the state cannot deliver an older snapshot to the next listener`() {
        tracker.track(KEY, object : ContentUploadStateTracker.UpdateListener {
            override fun onUpdate(state: State) {
                if (state is State.Uploading) tracker.setSuccess(KEY)
            }
        })
        tracker.track(KEY, listener)
        tracker.setProgress(KEY, 8_192, 65_536)
        idle()
        assertTrue(states.all { it is State.Success })
    }

    @Test
    fun `late byte callbacks cannot overwrite success or failure`() {
        track()
        tracker.setSuccess(KEY)
        tracker.setUploadStarted(KEY, true)
        tracker.setProgress(KEY, 8_192, 65_536)
        idle()
        assertEquals(State.Success, states.last())
        tracker.reset(KEY)
        val failure = IllegalStateException("refused")
        tracker.setFailure(KEY, failure)
        tracker.setProgressThumbnail(KEY, 8_192, 65_536)
        idle()
        assertEquals(State.Failure(failure), states.last())
    }

    @Test
    fun `concurrent gallery workers retain every item contribution`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val sizes = List(4) { 1_000L }
            (0..3).map { index ->
                executor.submit {
                    for (bytes in 1L..1_000L) tracker.setGalleryProgress(KEY, index, sizes, bytes, 1_000)
                }
            }.forEach { it.get(10, TimeUnit.SECONDS) }
            track()
            val state = states.last() as State.UploadingGalleryItem
            assertEquals(4_000L, state.overallCurrent)
            assertEquals(4_000L, state.overallTotal)
            assertFalse(state.isRetrying)
        } finally {
            executor.shutdownNow()
        }
    }

    private companion object {
        const val KEY = "\$local.upload-example"
    }
}
