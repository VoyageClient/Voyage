/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.View.MeasureSpec
import androidx.core.view.ViewCompat
import im.vector.app.R
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EmojiPanelHostLayoutTest {

    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val host = EmojiPanelHostLayout(activity).apply {
        addView(View(activity))
        activity.setContentView(this)
        shouldHoldHeightOnFocusLoss = { appLeaving }
    }

    private var appLeaving = true

    @Test
    fun `holds its height while the window has no focus`() {
        layoutAt(KEYBOARD_UP)

        host.onWindowFocusChanged(false)
        measureAt(FULL) shouldBeEqualTo KEYBOARD_UP
    }

    @Test
    fun `keeps the hold when a layout runs before the system overlay resizes the window`() {
        shadowOf(Looper.getMainLooper()).idle()
        layoutAt(KEYBOARD_UP)
        host.onWindowFocusChanged(false)
        measureAt(KEYBOARD_UP) shouldBeEqualTo KEYBOARD_UP
        shadowOf(Looper.getMainLooper()).idle()

        measureAt(FULL) shouldBeEqualTo KEYBOARD_UP
    }

    @Test
    fun `follows the window again once it is really back to the held height`() {
        layoutAt(KEYBOARD_UP)
        host.onWindowFocusChanged(false)
        measureAt(FULL) shouldBeEqualTo KEYBOARD_UP

        host.onWindowFocusChanged(true)
        // The keyboard came back with the focus: the same height as before, and the freeze is done.
        measureAt(KEYBOARD_UP) shouldBeEqualTo KEYBOARD_UP
        shadowOf(Looper.getMainLooper()).idle()
        measureAt(FULL) shouldBeEqualTo FULL
    }

    @Test
    fun `focus regain releases a settled hold without needing another measure`() {
        layoutAt(KEYBOARD_UP)
        host.onWindowFocusChanged(false)
        host.onWindowFocusChanged(true)

        host.isHeightFrozen shouldBeEqualTo false
        host.canTranslateKeyboard shouldBeEqualTo true
    }

    @Test
    fun `gives the space back when one of our own windows takes the focus`() {
        appLeaving = false
        layoutAt(KEYBOARD_UP)

        host.onWindowFocusChanged(false)
        measureAt(FULL) shouldBeEqualTo FULL
    }

    @Test
    fun `lets go after the grace period when the keyboard does not come back`() {
        layoutAt(KEYBOARD_UP)
        host.onWindowFocusChanged(false)
        measureAt(FULL) shouldBeEqualTo KEYBOARD_UP

        host.onWindowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        measureAt(FULL) shouldBeEqualTo FULL
    }

    @Test
    fun `drops the held height when the window changes width`() {
        layoutAt(KEYBOARD_UP)
        host.onWindowFocusChanged(false)

        host.measure(MeasureSpec.makeMeasureSpec(WIDTH * 2, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(FULL, MeasureSpec.EXACTLY))
        host.measuredHeight shouldBeEqualTo FULL
    }

    @Test
    fun `keyboard frames translate the room without laying it out and keep headers stationary`() {
        val toolbar = View(activity).apply { id = R.id.appBarLayout }
        val timeline = View(activity).apply { id = R.id.timelineRecyclerView }
        host.addView(toolbar)
        host.addView(timeline)
        ReflectionHelpers.callInstanceMethod<Unit>(host, "onFinishInflate")
        layoutAt(FULL)

        host.setKeyboardTranslation(-400f)

        host.translationY shouldBeEqualTo -400f
        toolbar.translationY shouldBeEqualTo 400f
        ViewCompat.getClipBounds(timeline)?.top shouldBeEqualTo 400
        host.isLayoutRequested shouldBeEqualTo false

        host.setKeyboardTranslation(0f)

        toolbar.translationY shouldBeEqualTo 0f
        ViewCompat.getClipBounds(timeline) shouldBeEqualTo null
    }

    @Test
    fun `finishing the keyboard animation resets translation in the final layout`() {
        layoutAt(FULL)
        host.setKeyboardTranslation(-400f)

        host.finishKeyboardAnimation()
        host.translationY shouldBeEqualTo -400f

        layoutAt(KEYBOARD_UP)
        host.translationY shouldBeEqualTo 0f
    }

    @Test
    fun `restoring a keyboard into held space does not shift the room down`() {
        layoutAt(KEYBOARD_UP)
        host.setKeyboardTranslation(400f)

        host.translationY shouldBeEqualTo 0f
        host.isLayoutRequested shouldBeEqualTo false
    }

    private fun layoutAt(height: Int) {
        measureAt(height)
        host.layout(0, 0, WIDTH, height)
    }

    private fun measureAt(height: Int): Int {
        host.measure(
                MeasureSpec.makeMeasureSpec(WIDTH, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        return host.measuredHeight
    }

    companion object {
        private const val WIDTH = 1080
        private const val FULL = 2216
        private const val KEYBOARD_UP = 1437
    }
}
