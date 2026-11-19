/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.EpoxyControllerAdapter
import com.airbnb.epoxy.EpoxyModel
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.item.ItemWithEvents
import im.vector.app.features.home.room.detail.timeline.item.TypingItem_
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScrollOnNewMessageCallbackTest {
    private val recycler = mockk<RecyclerView>(relaxed = true)
    private val layout = mockk<LinearLayoutManager>(relaxed = true)
    private val controller = mockk<TimelineEventController>(relaxed = true)
    private val adapter = mockk<EpoxyControllerAdapter>(relaxed = true)
    private val newItem = mockk<EpoxyModel<*>>(relaxed = true, moreInterfaces = arrayOf(ItemWithEvents::class))

    private fun callback(): ScrollOnNewMessageCallback {
        every { controller.adapter } returns adapter
        every { adapter.getModelAtPosition(0) } returns newItem
        every { (newItem as ItemWithEvents).getEventIds() } returns listOf("\$new")
        every { layout.findFirstVisibleItemPosition() } returns 0
        return ScrollOnNewMessageCallback(recycler, layout, controller)
    }

    @Test
    fun `a new message while slightly scrolled up lands flush with the bottom`() {
        val callback = callback()
        callback.addNewTimelineEventIds(listOf("\$new"))

        callback.onInserted(0, 1)

        verify(exactly = 1) { layout.scrollToPositionWithOffset(0, 0) }
        verify(exactly = 0) { layout.scrollToPosition(any()) }
    }

    @Test
    fun `a forced scroll lands flush with the bottom`() {
        val callback = callback()
        callback.forceScrollOnNextUpdate()

        callback.onInserted(0, 1)

        verify(exactly = 1) { layout.scrollToPositionWithOffset(0, 0) }
        verify(exactly = 0) { layout.scrollToPosition(any()) }
    }

    @Test
    fun `a new message landing above the typing spacer scrolls to the bottom`() {
        val callback = callback()
        every { adapter.getModelAtPosition(0) } returns mockk<TypingItem_>(relaxed = true)
        every { adapter.getModelAtPosition(1) } returns newItem
        callback.addNewTimelineEventIds(listOf("\$new"))

        callback.onInserted(1, 1)

        verify(exactly = 1) { layout.scrollToPositionWithOffset(0, 0) }
    }

    @Test
    fun `an insertion above the newest message does not scroll`() {
        val callback = callback()
        val newest = mockk<EpoxyModel<*>>(relaxed = true, moreInterfaces = arrayOf(ItemWithEvents::class))
        every { adapter.getModelAtPosition(0) } returns newest
        every { adapter.getModelAtPosition(1) } returns newItem
        callback.addNewTimelineEventIds(listOf("\$new"))

        callback.onInserted(1, 1)

        verify(exactly = 0) { layout.scrollToPositionWithOffset(any(), any()) }
    }

    @Test
    fun `an insertion while reading further back does not scroll`() {
        val callback = callback()
        every { layout.findFirstVisibleItemPosition() } returns 5
        callback.addNewTimelineEventIds(listOf("\$new"))

        callback.onInserted(0, 1)

        verify(exactly = 0) { layout.scrollToPositionWithOffset(any(), any()) }
    }
}
