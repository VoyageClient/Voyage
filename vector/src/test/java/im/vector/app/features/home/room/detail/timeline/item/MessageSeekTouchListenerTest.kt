/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.SeekBar
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class MessageSeekTouchListenerTest {
    private val context = RuntimeEnvironment.getApplication()
    private var longClicks = 0
    private var seeks = 0
    private var moves = 0
    private var hapticFeedback = 0
    private val view = object : View(context) {
        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            hapticFeedback++
            return true
        }
    }.apply {
        setOnLongClickListener {
            longClicks++
            true
        }
    }
    private val listener = MessageSeekTouchListener(view) {
        if (it.actionMasked == MotionEvent.ACTION_UP) seeks++
        if (it.actionMasked == MotionEvent.ACTION_MOVE) moves++
    }.also { view.setOnTouchListener(it) }
    private val downTime = SystemClock.uptimeMillis()

    @Test
    fun `holding the waveform opens actions without seeking on release`() {
        touch(MotionEvent.ACTION_DOWN)
        waitForLongPress()
        touch(MotionEvent.ACTION_UP)

        assertEquals(1, longClicks)
        assertEquals(0, seeks)
        assertEquals(1, hapticFeedback)
    }

    @Test
    fun `a tap and a drag still seek`() {
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_UP)
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_MOVE, 150f)
        waitForLongPress()
        touch(MotionEvent.ACTION_UP, 150f)

        assertEquals(0, longClicks)
        assertEquals(2, seeks)
    }

    @Test
    fun `recycling cancels a pending long press`() {
        touch(MotionEvent.ACTION_DOWN)
        listener.dispose()
        waitForLongPress()

        assertEquals(0, longClicks)
        assertEquals(0, seeks)
    }

    @Test
    fun `a tap after a long press seeks normally`() {
        touch(MotionEvent.ACTION_DOWN)
        waitForLongPress()
        touch(MotionEvent.ACTION_UP)
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_UP)

        assertEquals(1, longClicks)
        assertEquals(1, seeks)
    }

    @Test
    fun `small finger movements during a hold do not seek`() {
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_MOVE, 21f)
        waitForLongPress()
        touch(MotionEvent.ACTION_UP, 21f)

        assertEquals(1, longClicks)
        assertEquals(0, moves)
        assertEquals(0, seeks)
    }

    @Test
    fun `a native seek bar receives cancellation when held`() {
        val bar = SeekBar(context).apply {
            max = 100
            layout(0, 0, 300, 60)
            setOnLongClickListener {
                longClicks++
                true
            }
        }
        var cancelled = false
        bar.setOnTouchListener(MessageSeekTouchListener(bar) {
            cancelled = it.actionMasked == MotionEvent.ACTION_CANCEL
            bar.onTouchEvent(it)
        })
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = Unit
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (!cancelled) seeks++
            }
        })

        touch(MotionEvent.ACTION_DOWN, target = bar)
        waitForLongPress()
        touch(MotionEvent.ACTION_UP, target = bar)

        assertEquals(1, longClicks)
        assertEquals(0, seeks)
        assertEquals(false, bar.isPressed)
    }

    private fun touch(action: Int, x: Float = 20f, target: View = view) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, 20f, 0)
        target.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun waitForLongPress() {
        ShadowLooper.idleMainLooper((ViewConfiguration.getLongPressTimeout() + ViewConfiguration.getTapTimeout() + 100).toLong(), TimeUnit.MILLISECONDS)
    }
}
