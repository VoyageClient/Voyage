/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val VIEW_SIZE = 1000
private const val VIDEO_WIDTH = 200
private const val VIDEO_HEIGHT = 100

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoCropOverlayViewTest {

    private val view = VideoCropOverlayView(RuntimeEnvironment.getApplication()).apply {
        layout(0, 0, VIEW_SIZE, VIEW_SIZE)
    }

    /** Starts from a crop with room to move, since the whole frame has nowhere to go. */
    private fun givenVideo() {
        view.setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT)
        view.restoreEdits(0, RectF(0.25f, 0f, 0.75f, 1f))
        // The screen geometry the touch handling projects through is computed while drawing.
        view.draw(Canvas(Bitmap.createBitmap(VIEW_SIZE, VIEW_SIZE, Bitmap.Config.ARGB_8888)))
    }

    /** A drag reported as many small moves, the way a real finger arrives. */
    private fun dragBySteps(fromX: Float, fromY: Float, steps: List<Pair<Float, Float>>) {
        val events = buildList {
            add(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, fromX, fromY, 0))
            steps.forEachIndexed { index, (x, y) ->
                add(MotionEvent.obtain(0, 10L * (index + 1), MotionEvent.ACTION_MOVE, x, y, 0))
            }
            val (lastX, lastY) = steps.last()
            add(MotionEvent.obtain(0, 10L * (steps.size + 1), MotionEvent.ACTION_UP, lastX, lastY, 0))
        }
        events.forEach {
            view.onTouchEvent(it)
            it.recycle()
        }
    }

    private fun cropCenterX() = view.currentCrop()!!.centerX()

    @Test
    fun `a small drag keeps the crop held on the center line`() {
        givenVideo()
        view.snapToCenter = true

        dragBySteps(500f, 500f, (1..2).map { 500f + it * 4f to 500f })

        cropCenterX() shouldBeEqualTo 0.5f
    }

    @Test
    fun `dragging past the snap distance pulls the crop off the center line`() {
        givenVideo()
        view.snapToCenter = true

        // Each step on its own is well inside the snap distance; together they leave it.
        dragBySteps(500f, 500f, (1..10).map { 500f + it * 4f to 500f })

        (cropCenterX() > 0.5f) shouldBeEqualTo true
    }

    @Test
    fun `without snapping the crop follows the finger from the first move`() {
        givenVideo()

        dragBySteps(500f, 500f, (1..2).map { 500f + it * 4f to 500f })

        (cropCenterX() > 0.5f) shouldBeEqualTo true
    }

    @Test
    fun `a crop can be dragged far below a twentieth of the frame`() {
        givenVideo()

        // The bottom right handle, pulled onto the corner it is anchored on.
        dragBySteps(720f, 720f, listOf(285f to 285f))

        view.currentCrop()!!.run {
            (width() < 0.02f) shouldBeEqualTo true
            (height() < 0.03f) shouldBeEqualTo true
            (width() > 0f) shouldBeEqualTo true
            (height() > 0f) shouldBeEqualTo true
        }
    }
}
