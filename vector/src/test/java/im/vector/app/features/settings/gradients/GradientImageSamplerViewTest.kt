/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.gradients

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GradientImageSamplerViewTest {
    private val view = GradientImageSamplerView(RuntimeEnvironment.getApplication())

    // Left half red, right half blue, shown 1:1 so view and image coordinates match.
    private val image = Bitmap.createBitmap(1000, 100, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until width) for (y in 0 until height) setPixel(x, y, if (x < 500) Color.RED else Color.BLUE)
    }

    private fun showImage() {
        view.setBitmap(image)
        view.layout(0, 0, image.width, image.height)
        view.draw(Canvas(Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)))
    }

    private fun touch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun tap(x: Float, y: Float) {
        touch(MotionEvent.ACTION_DOWN, x, y)
        touch(MotionEvent.ACTION_UP, x, y)
    }

    @Test
    fun `it starts with no points and a tap places one where it lands`() {
        showImage()
        view.colors shouldBeEqualTo emptyList()

        tap(100f, 50f)
        tap(900f, 50f)

        view.colors shouldBeEqualTo listOf(Color.RED, Color.BLUE)
        view.selectedIndex shouldBeEqualTo 1
    }

    @Test
    fun `dragging a point moves it instead of placing another`() {
        showImage()
        tap(100f, 50f)

        touch(MotionEvent.ACTION_DOWN, 100f, 50f)
        touch(MotionEvent.ACTION_MOVE, 500f, 50f)
        touch(MotionEvent.ACTION_MOVE, 900f, 50f)
        touch(MotionEvent.ACTION_UP, 900f, 50f)

        view.colors shouldBeEqualTo listOf(Color.BLUE)
    }

    @Test
    fun `tapping an existing point selects it`() {
        showImage()
        tap(100f, 50f)
        tap(900f, 50f)

        tap(105f, 50f)

        view.selectedIndex shouldBeEqualTo 0
        view.colors.size shouldBeEqualTo 2
    }

    @Test
    fun `no point is placed past the limit, except that a lone eyedropper point moves`() {
        view.maxPoints = 1
        showImage()
        tap(100f, 50f)
        tap(900f, 50f)

        view.colors shouldBeEqualTo listOf(Color.BLUE)
    }

    @Test
    fun `reordering keeps the selection on the same point`() {
        view.setBitmap(image)
        view.restorePoints(floatArrayOf(0.1f, 0.5f, 0.9f, 0.5f, 0.2f, 0.5f), selected = 0)

        view.movePoint(0, 2)

        view.colors shouldBeEqualTo listOf(Color.BLUE, Color.RED, Color.RED)
        view.selectedIndex shouldBeEqualTo 2
    }

    @Test
    fun `every point can be removed`() {
        view.setBitmap(image)
        view.restorePoints(floatArrayOf(0.1f, 0.5f), selected = 0)

        view.removeSelected()

        view.colors shouldBeEqualTo emptyList()
        view.selectedIndex shouldBeEqualTo -1
    }
}
