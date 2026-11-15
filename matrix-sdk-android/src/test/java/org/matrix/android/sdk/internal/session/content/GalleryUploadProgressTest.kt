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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class GalleryUploadProgressTest {

    private val tracker = DefaultContentUploadStateTracker(
            MatrixCoroutineDispatchers(
                    io = Dispatchers.IO,
                    computation = Dispatchers.Default,
                    main = Dispatchers.Main,
                    crypto = Dispatchers.Default,
                    dmVerif = Dispatchers.Default,
            )
    )
    private val states = mutableListOf<ContentUploadStateTracker.State>()

    private val sizes = listOf(1_000L, 9_000L)

    private fun track(key: String = KEY) {
        tracker.track(key, object : ContentUploadStateTracker.UpdateListener {
            override fun onUpdate(state: ContentUploadStateTracker.State) {
                states.add(state)
            }
        })
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun latest() = states.last()

    @Test
    fun `aggregate weights items by their declared size`() {
        track()
        tracker.setGalleryProgress(KEY, itemIndex = 0, sizes = sizes, itemCurrent = 500, itemTotal = 1_000)
        idle()
        val half = latest() as ContentUploadStateTracker.State.UploadingGalleryItem
        assertEquals(500L, half.overallCurrent)
        assertEquals(10_000L, half.overallTotal)

        tracker.setGalleryProgress(KEY, itemIndex = 1, sizes = sizes, itemCurrent = 4_500, itemTotal = 9_000)
        idle()
        val second = latest() as ContentUploadStateTracker.State.UploadingGalleryItem
        assertEquals(500L + 4_500L, second.overallCurrent)
        assertEquals(1, second.itemIndex)
        assertEquals(2, second.itemCount)
    }

    @Test
    fun `interleaved reports sum each item without assuming earlier items finished`() {
        track()
        tracker.setGalleryProgress(KEY, itemIndex = 1, sizes = sizes, itemCurrent = 9_000, itemTotal = 9_000)
        idle()
        val ahead = (latest() as ContentUploadStateTracker.State.UploadingGalleryItem).overallCurrent

        tracker.setGalleryProgress(KEY, itemIndex = 0, sizes = sizes, itemCurrent = 10, itemTotal = 1_000)
        idle()
        assertEquals(ahead + 10, (latest() as ContentUploadStateTracker.State.UploadingGalleryItem).overallCurrent)
    }

    @Test
    fun `nothing revives the bar once the send has succeeded`() {
        track()
        tracker.setSuccess(KEY)
        idle()
        val afterSuccess = states.size

        tracker.setGalleryProgress(KEY, itemIndex = 0, sizes = sizes, itemCurrent = 1, itemTotal = 1_000)
        idle()
        assertEquals(afterSuccess, states.size)
        assertTrue(latest() is ContentUploadStateTracker.State.Success)
    }

    @Test
    fun `a sibling still uploading does not paint over a failed item`() {
        track()
        tracker.setFailure(KEY, Throwable("nope"))
        idle()
        val afterFailure = states.size

        tracker.setGalleryProgress(KEY, itemIndex = 1, sizes = sizes, itemCurrent = 500, itemTotal = 9_000)
        idle()
        assertEquals(afterFailure, states.size)
        assertTrue(latest() is ContentUploadStateTracker.State.Failure)
    }

    @Test
    fun `the send ends only once every item has settled`() {
        track()
        tracker.setGalleryItemSettled(KEY, itemIndex = 1, sizes = sizes)
        idle()
        assertTrue(latest() is ContentUploadStateTracker.State.UploadingGalleryItem)

        tracker.setGalleryItemSettled(KEY, itemIndex = 0, sizes = sizes)
        idle()
        assertTrue(latest() is ContentUploadStateTracker.State.Success)
    }

    @Test
    fun `settling the same item twice does not end the send`() {
        track()
        tracker.setGalleryItemSettled(KEY, itemIndex = 0, sizes = sizes)
        tracker.setGalleryItemSettled(KEY, itemIndex = 0, sizes = sizes)
        idle()
        assertTrue(latest() is ContentUploadStateTracker.State.UploadingGalleryItem)
    }

    @Test
    fun `preparation does not count any main upload bytes`() {
        track()
        tracker.setGalleryProgress(KEY, itemIndex = 1, sizes = sizes, itemCurrent = 0, itemTotal = 0)
        idle()
        val pinned = latest() as ContentUploadStateTracker.State.UploadingGalleryItem
        assertEquals(0L, pinned.overallCurrent)
    }

    @Test
    fun `large items do not lose precision`() {
        track(BIG_KEY)
        val big = listOf(4_000_000_000L, 4_000_000_000L)
        tracker.setGalleryProgress(BIG_KEY, itemIndex = 1, sizes = big, itemCurrent = 3_000_000_000L, itemTotal = 4_000_000_000L)
        idle()
        val state = latest() as ContentUploadStateTracker.State.UploadingGalleryItem
        assertEquals(3_000_000_000L, state.overallCurrent)
        assertEquals(8_000_000_000L, state.overallTotal)
    }

    @Test
    fun `retrying one item preserves its contribution and the other item progress`() {
        track()
        tracker.setGalleryProgress(KEY, 0, sizes, 800, 1_000)
        tracker.setGalleryProgress(KEY, 1, sizes, 4_000, 9_000)
        idle()
        tracker.setUploadRetrying(KEY, itemIndex = 1, sizes = sizes)
        tracker.setUploadStarted(KEY, true, itemIndex = 1, sizes = sizes)
        tracker.setGalleryProgress(KEY, 1, sizes, 100, 9_000)
        idle()
        val retry = latest() as ContentUploadStateTracker.State.UploadingGalleryItem
        assertEquals(4_800L, retry.overallCurrent)
        assertTrue(retry.isRetrying)
        tracker.setGalleryProgress(KEY, 1, sizes, 4_500, 9_000)
        idle()
        val resumed = latest() as ContentUploadStateTracker.State.UploadingGalleryItem
        assertEquals(5_300L, resumed.overallCurrent)
        assertFalse(resumed.isRetrying)
    }

    @Test
    fun `completed count follows server acceptance rather than bytes written`() {
        track()
        tracker.setGalleryProgress(KEY, 0, sizes, 1_000, 1_000)
        tracker.setUploadAwaitingResponse(KEY, itemIndex = 0, sizes = sizes)
        idle()
        assertEquals(0, (latest() as ContentUploadStateTracker.State.UploadingGalleryItem).completedCount)
        tracker.setGalleryItemSettled(KEY, 0, sizes)
        idle()
        assertEquals(1, (latest() as ContentUploadStateTracker.State.UploadingGalleryItem).completedCount)
    }

    @Test
    fun `late reports from a settled item cannot revive retry status`() {
        track()
        tracker.setGalleryItemSettled(KEY, 0, sizes)
        tracker.setUploadRetrying(KEY, itemIndex = 0, sizes = sizes)
        tracker.setGalleryProgress(KEY, 0, sizes, 1, 1_000)
        idle()
        val state = latest() as ContentUploadStateTracker.State.UploadingGalleryItem
        assertEquals(1_000L, state.overallCurrent)
        assertFalse(state.isRetrying)
    }

    @Test
    fun `a reset allows a failed gallery to be explicitly resent`() {
        track()
        tracker.setGalleryProgress(KEY, 0, sizes, 500, 1_000)
        tracker.setFailure(KEY, Throwable("unavailable"))
        idle()
        tracker.reset(KEY)
        tracker.setGalleryItemSettled(KEY, 0, sizes)
        tracker.setGalleryProgress(KEY, 1, sizes, 100, 9_000)
        idle()
        val state = latest() as ContentUploadStateTracker.State.UploadingGalleryItem
        assertEquals(1_100L, state.overallCurrent)
        assertEquals(1, state.completedCount)
    }

    @Test
    fun `unknown gallery sizes use actual upload lengths once all are known`() {
        track()
        tracker.setGalleryProgress(KEY, 0, listOf(0L, -1L), 50, 100)
        idle()
        val state = latest() as ContentUploadStateTracker.State.UploadingGalleryItem
        assertEquals(0L, state.overallTotal)
        assertEquals(50L, state.overallCurrent)
        tracker.setGalleryProgress(KEY, 1, listOf(0L, -1L), 100, 200)
        idle()
        val known = latest() as ContentUploadStateTracker.State.UploadingGalleryItem
        assertEquals(300L, known.overallTotal)
        assertEquals(150L, known.overallCurrent)
    }

    companion object {
        private const val KEY = "\$event"
        private const val BIG_KEY = "\$big"
    }
}
