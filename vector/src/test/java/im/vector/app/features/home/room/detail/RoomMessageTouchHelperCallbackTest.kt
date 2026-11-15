/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail

import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.EpoxyModel
import com.airbnb.epoxy.EpoxyViewHolder
import com.airbnb.epoxy.SimpleEpoxyController
import im.vector.app.features.home.room.detail.timeline.item.ReadReceiptsItem
import im.vector.app.features.settings.MessageSwipeAction
import im.vector.app.features.settings.MessageSwipeBindings
import im.vector.app.features.settings.MessageSwipeSlot
import im.vector.lib.core.utils.timer.Clock
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 28])
class RoomMessageTouchHelperCallbackTest {
    private val context = RuntimeEnvironment.getApplication().apply {
        setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light)
    }
    private val recycler = RecyclerView(context)
    private val handler = mockk<RoomMessageTouchHelperCallback.Handler>(relaxed = true) {
        every { availableActions(any(), any()) } answers { secondArg() }
    }
    private val clock = mockk<Clock> { every { epochMillis() } returns 1_000L }
    private var bindings = MessageSwipeBindings(
            mapOf(MessageSwipeSlot.LEFT_FAR to MessageSwipeAction.REDACT, MessageSwipeSlot.RIGHT to MessageSwipeAction.NONE)
    )
    private val callback = RoomMessageTouchHelperCallback(context, handler, clock) {
        bindings
    }.also { it.attachToRecyclerView(recycler) }
    private var lastTouchX = 300f

    @Test
    fun `a finishing animation cannot clear another message's swipe`() {
        val first = holder()
        val second = holder()
        draw(first, -120f)
        touch(MotionEvent.ACTION_UP)
        draw(second, -190f)
        callback.clearView(recycler, first)
        touch(MotionEvent.ACTION_UP)
        verify(exactly = 1) { handler.performAction(first.model, MessageSwipeAction.REPLY) }
        verify(exactly = 1) { handler.performAction(second.model, MessageSwipeAction.REDACT) }
    }

    @Test
    fun `release position replaces the last drawn action`() {
        val holder = holder()
        callback.onSelectedChanged(holder, ItemTouchHelper.ACTION_STATE_SWIPE)
        touch(MotionEvent.ACTION_MOVE, 100f)
        draw(holder, -190f, start = false)
        touch(MotionEvent.ACTION_UP, 170f)
        verify(exactly = 1) { handler.performAction(holder.model, MessageSwipeAction.REPLY) }
        verify(exactly = 0) { handler.performAction(any(), MessageSwipeAction.REDACT) }
    }

    @Test
    fun `overswiping with no farther binding releases the regular action`() {
        bindings = MessageSwipeBindings(mapOf(MessageSwipeSlot.LEFT_FAR to MessageSwipeAction.NONE))
        val holder = holder()
        draw(holder, -220f)
        touch(MotionEvent.ACTION_UP)
        verify(exactly = 1) { handler.performAction(holder.model, MessageSwipeAction.REPLY) }
    }

    @Test
    fun `an unavailable regular action promotes the farther action`() {
        every { handler.availableActions(any(), any()) } returns setOf(MessageSwipeAction.REDACT)
        val holder = holder()
        draw(holder, -120f)
        touch(MotionEvent.ACTION_UP)
        verify(exactly = 1) { handler.performAction(holder.model, MessageSwipeAction.REDACT) }
    }

    @Test
    fun `no available actions disable movement`() {
        every { handler.availableActions(any(), any()) } returns emptySet()
        callback.getMovementFlags(recycler, holder()) shouldBeEqualTo 0
    }

    @Test
    fun `read receipt rows never advertise swipe actions for their associated message`() {
        val holder = receiptHolder()

        callback.getMovementFlags(recycler, holder) shouldBeEqualTo 0

        verify(exactly = 0) { handler.availableActions(any(), any()) }
    }

    @Test
    fun `a swipe selected on a receipt row cannot invoke its associated message action`() {
        val holder = receiptHolder()

        draw(holder, -190f)
        touch(MotionEvent.ACTION_UP)

        verify(exactly = 0) { handler.performAction(any(), any()) }
    }

    @Test
    fun `permissions lost during a gesture fall back to the remaining action`() {
        val holder = holder()
        draw(holder, -190f)
        every { handler.availableActions(any(), any()) } returns setOf(MessageSwipeAction.REPLY)
        touch(MotionEvent.ACTION_UP)
        verify(exactly = 1) { handler.performAction(holder.model, MessageSwipeAction.REPLY) }
        verify(exactly = 0) { handler.performAction(any(), MessageSwipeAction.REDACT) }
    }

    @Test
    fun `losing all permissions during a gesture cancels its action`() {
        val holder = holder()
        draw(holder, -190f)
        every { handler.availableActions(any(), any()) } returns emptySet()
        touch(MotionEvent.ACTION_UP)
        verify(exactly = 0) { handler.performAction(any(), any()) }
    }

    @Test
    fun `crossing an unbound direction does not shift the gesture origin`() {
        val holder = holder()
        callback.onSelectedChanged(holder, ItemTouchHelper.ACTION_STATE_SWIPE)
        touch(MotionEvent.ACTION_MOVE, 100f)
        draw(holder, -120f, start = false)
        touch(MotionEvent.ACTION_MOVE, 240f)
        draw(holder, 0f, start = false)
        touch(MotionEvent.ACTION_UP, 50f)
        verify(exactly = 1) { handler.performAction(holder.model, MessageSwipeAction.REPLY) }
        verify(exactly = 0) { handler.performAction(any(), MessageSwipeAction.REDACT) }
    }

    @Test
    fun `tapping a returning message without moving does not repeat its action`() {
        val holder = holder()
        draw(holder, -120f)
        touch(MotionEvent.ACTION_UP)
        callback.onSelectedChanged(holder, ItemTouchHelper.ACTION_STATE_SWIPE)
        draw(holder, -120f, start = false)
        touch(MotionEvent.ACTION_UP)
        verify(exactly = 1) { handler.performAction(any(), any()) }
    }

    @Test
    fun `cancelled and multitouch gestures do not perform actions`() {
        val holder = holder()
        draw(holder, -190f)
        touch(MotionEvent.ACTION_CANCEL)
        touch(MotionEvent.ACTION_UP)
        draw(holder, -190f)
        touch(MotionEvent.ACTION_POINTER_DOWN)
        touch(MotionEvent.ACTION_UP)
        verify(exactly = 0) { handler.performAction(any(), any()) }
    }

    @Test
    fun `rebinding a holder to another message cancels its action even before the next draw`() {
        val holder = holder()
        draw(holder, -190f)
        holder.bind(model(), null, emptyList(), 0)
        touch(MotionEvent.ACTION_UP)
        verify(exactly = 0) { handler.performAction(any(), any()) }
    }

    @Test
    fun `an unbound holder cannot release a pending action`() {
        val holder = holder()
        draw(holder, -190f)
        holder.unbind()
        touch(MotionEvent.ACTION_UP)
        verify(exactly = 0) { handler.performAction(any(), any()) }
    }

    @Test
    fun `dispatched touch events use the final finger position before the next frame`() {
        val model = model().id("sample-message")
        val controller = SimpleEpoxyController().apply { setModels(listOf(model)) }
        recycler.layoutManager = LinearLayoutManager(context)
        recycler.adapter = controller.adapter
        val width = (600 * context.resources.displayMetrics.density).toInt()
        recycler.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
        recycler.layout(0, 0, width, 300)
        dispatch(MotionEvent.ACTION_DOWN, 500f)
        dispatch(MotionEvent.ACTION_MOVE, 480f)
        dispatch(MotionEvent.ACTION_MOVE, 380f)
        recycler.draw(Canvas())
        dispatch(MotionEvent.ACTION_UP, 310f)
        verify(exactly = 1) { handler.performAction(model, MessageSwipeAction.REDACT) }
        verify(exactly = 0) { handler.performAction(any(), MessageSwipeAction.REPLY) }
    }

    private fun model() = object : EpoxyModel<View>() {
        override fun getDefaultLayout() = android.R.layout.simple_list_item_1
    }

    private fun receiptHolder(): EpoxyViewHolder {
        val receipt = mockk<ReadReceiptsItem>(relaxed = true) {
            every { getEventIds() } returns listOf("\$message:example.org")
        }
        val view = View(context).apply { layout(0, 0, 600, 100) }
        return spyk(EpoxyViewHolder(recycler, view, false)).also {
            every { it.model } returns receipt
        }
    }

    private fun holder() = EpoxyViewHolder(recycler, View(context).apply { layout(0, 0, 600, 100) }, false).apply {
        bind(model(), null, emptyList(), 0)
    }

    private fun draw(holder: EpoxyViewHolder, distanceDp: Float, start: Boolean = true) {
        if (start) {
            callback.onSelectedChanged(holder, ItemTouchHelper.ACTION_STATE_SWIPE)
            touch(MotionEvent.ACTION_MOVE, 300f + distanceDp)
        }
        callback.onChildDraw(
                Canvas(), recycler, holder, distanceDp * context.resources.displayMetrics.density, 0f, ItemTouchHelper.ACTION_STATE_SWIPE, true
        )
    }

    private fun touch(action: Int, xDp: Float = lastTouchX) {
        lastTouchX = xDp
        val event = MotionEvent.obtain(0, 0, action, xDp * context.resources.displayMetrics.density, 0f, 0)
        try {
            shadowOf(recycler).onTouchListener.onTouch(recycler, event)
        } finally {
            event.recycle()
        }
    }

    private fun dispatch(action: Int, xDp: Float) {
        val event = MotionEvent.obtain(0, 100, action, xDp * context.resources.displayMetrics.density, 12f, 0)
        try {
            recycler.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
