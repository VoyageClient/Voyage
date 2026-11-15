/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.utils.TextUtils
import im.vector.app.features.home.room.detail.timeline.MessageColorProvider
import im.vector.lib.strings.CommonStrings
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.content.ContentUploadStateTracker
import org.matrix.android.sdk.api.session.content.ContentUploadStateTracker.State
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ContentMediaProgressUpdaterTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).get()
    private val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { id = R.id.mediaProgressBar }
    private val label = TextView(activity).apply { id = R.id.mediaProgressTextView }
    private val layout = FrameLayout(activity).apply {
        addView(bar)
        addView(label)
    }
    private val updater = ContentMediaProgressUpdater(layout, true, mockk<MessageColorProvider>(relaxed = true))

    @Test
    fun `retry status preserves the previous bar position`() {
        updater.onUpdate(State.Uploading(24_576, 65_536))
        val percent = bar.progress
        updater.onUpdate(State.Uploading(24_576, 65_536, isRetrying = true))
        assertEquals(percent, bar.progress)
        assertEquals(activity.getString(CommonStrings.send_file_step_retrying_upload), label.text.toString())
        updater.onUpdate(State.Uploading(32_768, 65_536))
        assertEquals(50, bar.progress)
        assertEquals(sending(32_768, 65_536), label.text.toString())
    }

    @Test
    fun `gallery label and bar both use aggregate bytes`() {
        updater.onUpdate(State.UploadingGalleryItem(1, 2, 70_000, 100_000, 78_192, 200_000))
        val text = label.text.toString()
        val percent = bar.progress
        updater.onUpdate(State.UploadingGalleryItem(0, 2, 8_192, 100_000, 78_192, 200_000))
        assertEquals(text, label.text.toString())
        assertEquals(percent, bar.progress)
        assertEquals("${sending(78_192, 200_000)} (0/2)", text)
    }

    @Test
    fun `completed gallery count stays stable while workers interleave`() {
        updater.onUpdate(State.UploadingGalleryItem(1, 3, 10, 100, 110, 300, completedCount = 1))
        assertTrue(label.text.toString().endsWith("(1/3)"))
        updater.onUpdate(State.UploadingGalleryItem(0, 3, 100, 100, 110, 300, completedCount = 1))
        assertTrue(label.text.toString().endsWith("(1/3)"))
    }

    @Test
    fun `uploaded bytes show waiting until server acceptance`() {
        updater.onUpdate(State.Uploading(65_536, 65_536, isAwaitingResponse = true))
        assertEquals(100, bar.progress)
        assertEquals(View.VISIBLE, layout.visibility)
        assertEquals(activity.getString(CommonStrings.send_file_step_waiting_for_server), label.text.toString())
        updater.onUpdate(State.Success)
        assertEquals(View.GONE, layout.visibility)
    }

    @Test
    fun `an unknown length remains indeterminate until the length is known`() {
        updater.onUpdate(State.Uploading(8_192, -1))
        assertTrue(bar.isIndeterminate)
        assertEquals(activity.getString(CommonStrings.send_file_step_sending_unknown_size), label.text.toString())
        updater.onUpdate(State.Uploading(8_192, 65_536))
        assertFalse(bar.isIndeterminate)
        assertEquals(12, bar.progress)
    }

    @Test
    fun `thumbnail retries use the same status handling`() {
        updater.onUpdate(State.UploadingThumbnail(16_384, 32_768, isRetrying = true))
        assertEquals(50, bar.progress)
        assertEquals(activity.getString(CommonStrings.send_file_step_retrying_upload), label.text.toString())
    }

    @Test
    fun `two visible copies of an event keep separate subscriptions`() {
        val tracker = mockk<ContentUploadStateTracker>(relaxed = true)
        val holder = mockk<ActiveSessionHolder>()
        val session = mockk<Session>()
        every { holder.getSafeActiveSession() } returns session
        every { session.contentUploadProgressTracker() } returns tracker
        val binder = ContentUploadStateTrackerBinder(holder, mockk(relaxed = true))
        val first = slot<ContentUploadStateTracker.UpdateListener>()
        every { tracker.track("event-example", capture(first)) } answers { }
        binder.bind("event-example", true, layout)
        val firstListener = first.captured
        val secondLayout = FrameLayout(activity).apply {
            addView(ProgressBar(activity).apply { id = R.id.mediaProgressBar })
            addView(TextView(activity).apply { id = R.id.mediaProgressTextView })
        }
        binder.bind("event-example", true, secondLayout)
        verify(exactly = 0) { tracker.untrack(any(), any()) }
        binder.unbind("event-example", layout)
        verify(exactly = 1) { tracker.untrack("event-example", firstListener) }
        binder.unbind("event-example", secondLayout)
        verify(exactly = 2) { tracker.untrack("event-example", any()) }
    }

    @Test
    fun `recycling a view replaces its subscription even if an old unbind arrives late`() {
        val tracker = mockk<ContentUploadStateTracker>(relaxed = true)
        val holder = mockk<ActiveSessionHolder>()
        val session = mockk<Session>()
        every { holder.getSafeActiveSession() } returns session
        every { session.contentUploadProgressTracker() } returns tracker
        val binder = ContentUploadStateTrackerBinder(holder, mockk(relaxed = true))
        binder.bind("event-old-example", true, layout)
        binder.bind("event-new-example", true, layout)
        verify(exactly = 1) { tracker.untrack("event-old-example", any()) }
        binder.unbind("event-old-example", layout)
        verify(exactly = 0) { tracker.untrack("event-new-example", any()) }
        binder.unbind("event-new-example", layout)
        verify(exactly = 1) { tracker.untrack("event-new-example", any()) }
    }

    @Test
    fun `a session change still detaches the old view subscription`() {
        val tracker = mockk<ContentUploadStateTracker>(relaxed = true)
        val holder = mockk<ActiveSessionHolder>()
        val session = mockk<Session>()
        every { holder.getSafeActiveSession() } returns session
        every { session.contentUploadProgressTracker() } returns tracker
        val binder = ContentUploadStateTrackerBinder(holder, mockk(relaxed = true))
        binder.bind("event-example", true, layout)
        every { holder.getSafeActiveSession() } returns null
        binder.unbind("event-example", layout)
        verify(exactly = 1) { tracker.untrack("event-example", any()) }
    }

    @Test
    fun `detaching the timeline leaves ongoing upload state intact`() {
        val tracker = mockk<ContentUploadStateTracker>(relaxed = true)
        val holder = mockk<ActiveSessionHolder>()
        val session = mockk<Session>()
        every { holder.getSafeActiveSession() } returns session
        every { session.contentUploadProgressTracker() } returns tracker
        val binder = ContentUploadStateTrackerBinder(holder, mockk(relaxed = true))
        binder.bind("event-example", true, layout)
        binder.clear()
        verify(exactly = 1) { tracker.untrack("event-example", any()) }
        verify(exactly = 0) { tracker.clear() }
    }

    private fun sending(current: Long, total: Long) = activity.getString(
            CommonStrings.send_file_step_sending_file,
            TextUtils.formatFileSize(activity, current, true),
            TextUtils.formatFileSize(activity, total, true),
    )
}
