/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.EpoxyControllerAdapter
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScrollOnHighlightedEventCallbackTest {
    private val recycler = mockk<RecyclerView>(relaxed = true)
    private val layout = mockk<LinearLayoutManager>(relaxed = true)
    private val controller = mockk<TimelineEventController>(relaxed = true)
    private val adapter = mockk<EpoxyControllerAdapter>(relaxed = true)
    private val observer = mockk<ViewTreeObserver>(relaxed = true)
    private val view = mockk<View>(relaxed = true)
    private val holder = mockk<RecyclerView.ViewHolder>(relaxed = true)
    private val preDraw = slot<ViewTreeObserver.OnPreDrawListener>()
    private var viewportHeight = 1782
    private var top = 1136
    private var height = 435
    private var canScroll = false
    private var landings = 0

    private fun callback(): ScrollOnHighlightedEventCallback {
        every { controller.timeline } returns null
        every { controller.adapter } returns adapter
        every { controller.findEventPositionInAdapter("target") } returns 1
        every { controller.findModelPositionInAdapter(42L) } returns 1
        every { controller.isNeighborhoodBuilt(any(), any()) } returns true
        every { adapter.getItemId(1) } returns 42L
        every { adapter.itemCount } returns 3
        every { recycler.viewTreeObserver } returns observer
        every { observer.addOnPreDrawListener(capture(preDraw)) } returns Unit
        every { recycler.height } answers { viewportHeight }
        every { recycler.getChildViewHolder(view) } returns holder
        every { holder.itemId } returns 42L
        every { holder.bindingAdapterPosition } returns 1
        every { layout.findViewByPosition(1) } returns view
        every { layout.getDecoratedTop(view) } answers { top }
        every { layout.getDecoratedBottom(view) } answers { top + height }
        every { layout.getDecoratedMeasuredHeight(view) } answers { height }
        every { recycler.scrollBy(0, any()) } answers { if (canScroll) top -= secondArg<Int>() }
        every { layout.offsetChildrenVertical(any()) } answers { top += firstArg<Int>() }
        return ScrollOnHighlightedEventCallback(recycler, layout, controller) { landings++ }
    }

    @Test
    fun `a correction blocked by the list boundary is not replayed after more rows arrive`() {
        val callback = callback()
        try {
            callback.scheduleScrollTo("target")
            preDraw.captured.onPreDraw()
            height = 1154
            top = 417
            preDraw.captured.onPreDraw()
            canScroll = true
            preDraw.captured.onPreDraw()
            assertEquals(417, top)
            verify(exactly = 1) { recycler.scrollBy(0, -719) }
            assertEquals(1, landings)
        } finally {
            callback.dispose()
        }
    }

    @Test
    fun `a temporary older loading edge does not move the landed message`() {
        top = 733
        height = 846
        val callback = callback()
        val timeline = mockk<Timeline>(relaxed = true)
        every { controller.timeline } returns timeline
        every { timeline.hasMoreToLoad(Timeline.Direction.BACKWARDS) } returns true
        try {
            callback.scheduleScrollTo("target")
            preDraw.captured.onPreDraw()
            top = 310
            preDraw.captured.onPreDraw()
            assertEquals(733, top)
            verify(exactly = 1) { layout.offsetChildrenVertical(423) }

            canScroll = true
            preDraw.captured.onPreDraw()
            assertEquals(733, top)
            verify(exactly = 1) { recycler.scrollBy(0, -423) }
        } finally {
            callback.dispose()
        }
    }

    @Test
    fun `a temporary newer loading edge does not move the landed message`() {
        top = 529
        height = 997
        val callback = callback()
        val timeline = mockk<Timeline>(relaxed = true)
        every { controller.timeline } returns timeline
        every { timeline.hasMoreToLoad(Timeline.Direction.FORWARDS) } returns true
        try {
            callback.scheduleScrollTo("target")
            preDraw.captured.onPreDraw()
            top = 582
            preDraw.captured.onPreDraw()
            assertEquals(529, top)
            verify(exactly = 1) { layout.offsetChildrenVertical(-53) }
        } finally {
            callback.dispose()
        }
    }

    @Test
    fun `real recycler keeps the target steady as both sides of a short context fill`() {
        val context = RuntimeEnvironment.getApplication()
        val rows = mutableListOf(1L to 211, 42L to 846, 2L to 310)
        val actualAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            init { setHasStableIds(true) }
            override fun getItemCount() = rows.size
            override fun getItemId(position: Int) = rows[position].first
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(View(context)) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                holder.itemView.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rows[position].second)
            }
        }
        val actualLayout = LinearLayoutManager(context, RecyclerView.VERTICAL, true)
        val actualRecycler = RecyclerView(context).apply {
            layoutManager = actualLayout
            adapter = actualAdapter
            itemAnimator = null
        }
        fun draw() {
            actualRecycler.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1790, View.MeasureSpec.EXACTLY))
            actualRecycler.layout(0, 0, 1080, 1790)
            actualRecycler.viewTreeObserver.dispatchOnPreDraw()
        }
        val timeline = mockk<Timeline>(relaxed = true)
        every { controller.timeline } returns timeline
        every { timeline.hasMoreToLoad(any()) } returns true
        every { controller.adapter } returns adapter
        every { adapter.itemCount } answers { rows.size }
        every { adapter.getItemId(any()) } answers { rows[firstArg<Int>()].first }
        every { controller.findEventPositionInAdapter("target") } answers { rows.indexOfFirst { it.first == 42L } }
        every { controller.findModelPositionInAdapter(42L) } answers { rows.indexOfFirst { it.first == 42L } }
        every { controller.isNeighborhoodBuilt(any(), any()) } returns true
        val callback = ScrollOnHighlightedEventCallback(actualRecycler, actualLayout, controller)
        fun targetTop() = actualLayout.getDecoratedTop(actualLayout.findViewByPosition(rows.indexOfFirst { it.first == 42L })!!)
        try {
            draw()
            callback.scheduleScrollTo("target")
            draw()
            assertEquals(733, targetTop())

            rows.addAll(0, (100L..119L).map { it to 100 })
            actualAdapter.notifyItemRangeInserted(0, 20)
            draw()
            assertEquals(733, targetTop())

            val previousSize = rows.size
            rows.addAll((200L..219L).map { it to 100 })
            actualAdapter.notifyItemRangeInserted(previousSize, 20)
            draw()
            assertEquals(733, targetTop())
        } finally {
            callback.dispose()
        }
    }

    @Test
    fun `viewport resize does not scroll the target back under the keyboard`() {
        top = 1426
        height = 145
        val callback = callback()
        try {
            callback.scheduleScrollTo("target")
            preDraw.captured.onPreDraw()
            viewportHeight = 1003
            top = 647
            canScroll = true
            preDraw.captured.onPreDraw()
            assertEquals(647, top)
            verify(exactly = 0) { recycler.scrollBy(any(), any()) }
        } finally {
            callback.dispose()
        }
    }

    @Test
    fun `landing is reported only when the target view is drawn`() {
        val callback = callback()
        try {
            callback.scheduleScrollTo("target")
            assertEquals(0, landings)
            preDraw.captured.onPreDraw()
            preDraw.captured.onPreDraw()
            assertEquals(1, landings)
        } finally {
            callback.dispose()
        }
    }

    @Test
    fun `missing target times out even if no adapter updates arrive`() {
        val callback = callback()
        every { controller.findEventPositionInAdapter("target") } returns null
        every { controller.searchPositionOfEventOrNearest("target") } returns null
        try {
            callback.scheduleScrollTo("target")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
            assertFalse(callback.isSettling())
            verify(exactly = 1) { controller.searchPositionOfEventOrNearest("target") }
        } finally {
            callback.dispose()
        }
    }
}
