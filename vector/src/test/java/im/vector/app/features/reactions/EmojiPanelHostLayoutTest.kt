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
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EmojiPanelHostLayoutTest {

    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val host = EmojiPanelHostLayout(activity).apply {
        addView(View(activity))
        activity.setContentView(this)
        isAppLeaving = { appLeaving }
    }

    private var appLeaving = true

    @Test
    fun `holds its height while the window has no focus`() {
        layoutAt(KEYBOARD_UP)

        host.onWindowFocusChanged(false)
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
