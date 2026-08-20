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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.session.content.ContentUploadStateTracker
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A gallery's items report from independent workers, so the aggregate has to hold up under
 * out-of-order reports.
 */
@RunWith(RobolectricTestRunner::class)
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
        // Half of a 1 kB item out of 10 kB total, not half of the gallery.
        assertEquals(500L, half.overallCurrent)
        assertEquals(10_000L, half.overallTotal)

        tracker.setGalleryProgress(KEY, itemIndex = 1, sizes = sizes, itemCurrent = 4_500, itemTotal = 9_000)
        idle()
        val second = latest() as ContentUploadStateTracker.State.UploadingGalleryItem
        assertEquals(1_000L + 4_500L, second.overallCurrent)
        assertEquals(1, second.itemIndex)
        assertEquals(2, second.itemCount)
    }

    @Test
    fun `a late report from an earlier item cannot walk the bar backwards`() {
        track()
        tracker.setGalleryProgress(KEY, itemIndex = 1, sizes = sizes, itemCurrent = 9_000, itemTotal = 9_000)
        idle()
        val ahead = (latest() as ContentUploadStateTracker.State.UploadingGalleryItem).overallCurrent

        tracker.setGalleryProgress(KEY, itemIndex = 0, sizes = sizes, itemCurrent = 10, itemTotal = 1_000)
        idle()
        assertEquals(ahead, (latest() as ContentUploadStateTracker.State.UploadingGalleryItem).overallCurrent)
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

        // Out of order, as independent workers finish: the count is what ends it, not the position.
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
    fun `a phase that is not uploading yet holds the item at its boundary`() {
        track()
        tracker.setGalleryProgress(KEY, itemIndex = 1, sizes = sizes, itemCurrent = 0, itemTotal = 0)
        idle()
        val pinned = latest() as ContentUploadStateTracker.State.UploadingGalleryItem
        assertEquals(1_000L, pinned.overallCurrent)
    }

    @Test
    fun `large items do not lose precision`() {
        track(BIG_KEY)
        val big = listOf(4_000_000_000L, 4_000_000_000L)
        tracker.setGalleryProgress(BIG_KEY, itemIndex = 1, sizes = big, itemCurrent = 3_000_000_000L, itemTotal = 4_000_000_000L)
        idle()
        val state = latest() as ContentUploadStateTracker.State.UploadingGalleryItem
        assertEquals(7_000_000_000L, state.overallCurrent)
        assertEquals(8_000_000_000L, state.overallTotal)
    }

    companion object {
        private const val KEY = "\$event"
        private const val BIG_KEY = "\$big"
    }
}
