/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Looper
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldBeLessOrEqualTo
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MediaPlaceholderPulseTest {

    private var invalidations = 0
    private val canvas = Canvas(Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888))

    private val placeholder = MediaPlaceholderDrawable(RuntimeEnvironment.getApplication()).apply {
        callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                invalidations++
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
            override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
        }
    }

    @After
    fun tearDown() {
        placeholder.setVisible(false, false)
        idle(100)
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun `a placeholder nothing draws stops asking for frames`() {
        placeholder.setBounds(0, 0, 100, 100)
        idle(500)
        val settled = invalidations

        idle(500)

        settled shouldBeLessOrEqualTo 3
        invalidations shouldBeEqualTo settled
    }

    @Test
    fun `a placeholder that is drawn keeps pulsing`() {
        placeholder.setBounds(0, 0, 100, 100)
        repeat(30) {
            idle(16)
            placeholder.draw(canvas)
        }

        invalidations shouldBeGreaterThan 20
    }

    @Test
    fun `drawing a stopped placeholder starts it again`() {
        placeholder.setBounds(0, 0, 100, 100)
        idle(500)
        val stopped = invalidations

        placeholder.draw(canvas)
        idle(16)

        invalidations shouldBeGreaterThan stopped
    }
}
