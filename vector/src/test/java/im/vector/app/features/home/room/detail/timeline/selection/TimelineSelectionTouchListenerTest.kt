/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.selection

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class TimelineSelectionTouchListenerTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private var active = false
    private val anchors = mutableListOf<String>()
    private val taps = mutableListOf<String>()
    private var range = emptyList<String>()
    private var releases = 0
    private val recycler = RecyclerView(activity).apply {
        layoutManager = LinearLayoutManager(activity)
        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 5
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(View(activity).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 80)
            }) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        }
        activity.setContentView(this)
        measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        layout(0, 0, 300, 400)
    }
    private val listener = TimelineSelectionTouchListener(recycler, object : TimelineSelectionTouchListener.Callback {
        override fun isSelectionActive() = active
        override fun selectableEventIdAt(adapterPosition: Int) = "event-$adapterPosition"
        override fun adapterPositionOf(eventId: String) = eventId.removePrefix("event-").toInt()
        override fun onTap(eventId: String) {
            taps.add(eventId)
        }
        override fun onAnchor(anchorEventId: String) {
            anchors.add(anchorEventId)
        }
        override fun onDragRange(eventIds: List<String>) {
            range = eventIds
        }
        override fun onAnchorReleased() {
            releases++
        }
    })
    private val downTime = SystemClock.uptimeMillis()

    @Test
    fun `holding and dragging outside selection never starts it`() {
        touch(MotionEvent.ACTION_DOWN)
        ShadowLooper.idleMainLooper(1000, TimeUnit.MILLISECONDS)
        assertFalse(touch(MotionEvent.ACTION_MOVE, 200f))
        touch(MotionEvent.ACTION_UP, 200f)

        assertTrue(anchors.isEmpty())
        assertTrue(taps.isEmpty())
        assertTrue(range.isEmpty())
    }

    @Test
    fun `holding and dragging in selection still selects a range`() {
        active = true
        touch(MotionEvent.ACTION_DOWN)
        ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
        assertTrue(touch(MotionEvent.ACTION_MOVE, 200f))

        assertEquals(listOf("event-0"), anchors)
        assertEquals(listOf("event-0", "event-1", "event-2"), range)
        listener.cancelGesture()
        assertEquals(1, releases)
    }

    @Test
    fun `a tap only toggles a row while selecting`() {
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_UP)
        assertTrue(taps.isEmpty())

        active = true
        touch(MotionEvent.ACTION_DOWN)
        assertTrue(touch(MotionEvent.ACTION_UP))
        assertEquals(listOf("event-0"), taps)
    }

    @Test
    fun `leaving selection before a pending hold cannot restart it`() {
        active = true
        touch(MotionEvent.ACTION_DOWN)
        active = false
        ShadowLooper.idleMainLooper(1000, TimeUnit.MILLISECONDS)
        assertFalse(touch(MotionEvent.ACTION_MOVE, 200f))
        touch(MotionEvent.ACTION_UP, 200f)

        assertTrue(anchors.isEmpty())
    }

    @Test
    fun `leaving selection stops an active range drag without another touch`() {
        active = true
        touch(MotionEvent.ACTION_DOWN)
        ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
        assertTrue(touch(MotionEvent.ACTION_MOVE, 390f))

        active = false
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)

        assertEquals(1, releases)
    }

    private fun touch(action: Int, y: Float = 40f): Boolean {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 150f, y, 0)
        val intercepted = listener.onInterceptTouchEvent(recycler, event)
        event.recycle()
        return intercepted
    }
}
