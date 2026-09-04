/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.image

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
import kotlin.math.abs

private const val VIEW_SIZE = 1000
private const val IMAGE_WIDTH = 200
private const val IMAGE_HEIGHT = 100

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImageEditorViewTest {

    private val view = ImageEditorView(RuntimeEnvironment.getApplication()).apply {
        layout(0, 0, VIEW_SIZE, VIEW_SIZE)
    }

    private fun givenImage() {
        view.setBitmap(Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888))
        // The screen geometry the touch handling projects through is computed while drawing.
        view.draw(Canvas(Bitmap.createBitmap(VIEW_SIZE, VIEW_SIZE, Bitmap.Config.ARGB_8888)))
    }

    private fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        dragBySteps(fromX, fromY, listOf(toX to toY))
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

    /** The crop's size in source pixels, which is what the locked ratio is really about. */
    private fun croppedRatio(): Float {
        val crop = view.currentEdits().crop
        val sideways = view.currentEdits().userRotation % 180 != 0
        val width = crop.width() * (if (sideways) IMAGE_HEIGHT else IMAGE_WIDTH)
        val height = crop.height() * (if (sideways) IMAGE_WIDTH else IMAGE_HEIGHT)
        return width / height
    }

    @Test
    fun `a locked square ratio centers the largest square crop`() {
        view.cropAspectRatio = 1f
        givenImage()

        view.currentEdits().crop.run {
            left shouldBeEqualTo 0.25f
            top shouldBeEqualTo 0f
            right shouldBeEqualTo 0.75f
            bottom shouldBeEqualTo 1f
        }
    }

    @Test
    fun `a locked banner ratio fits the image width`() {
        view.cropAspectRatio = 2.8f
        givenImage()

        (abs(croppedRatio() - 2.8f) < 0.001f) shouldBeEqualTo true
        view.currentEdits().crop.width() shouldBeEqualTo 1f
    }

    @Test
    fun `rotating keeps the locked ratio`() {
        view.cropAspectRatio = 1f
        givenImage()

        view.rotateClockwise()

        (abs(croppedRatio() - 1f) < 0.001f) shouldBeEqualTo true
    }

    @Test
    fun `without a locked ratio the crop covers the whole image`() {
        givenImage()

        view.currentEdits().crop.width() shouldBeEqualTo 1f
        view.currentEdits().crop.height() shouldBeEqualTo 1f
    }

    @Test
    fun `snapping pulls a drawn censor edge onto the center line`() {
        givenImage()
        view.tool = ImageEditorView.Tool.CENSOR
        view.snapToCenter = true

        // Ends just short of the view's horizontal middle, which the image is centered on.
        drag(200f, 400f, VIEW_SIZE / 2f - 5f, 500f)

        view.currentEdits().censors.single().right shouldBeEqualTo 0.5f
    }

    @Test
    fun `a small drag keeps the crop held on the center line`() {
        view.cropAspectRatio = 1f
        givenImage()
        view.snapToCenter = true

        dragBySteps(500f, 500f, (1..2).map { 500f + it * 4f to 500f })

        view.currentEdits().crop.centerX() shouldBeEqualTo 0.5f
    }

    @Test
    fun `dragging past the snap distance pulls the crop off the center line`() {
        view.cropAspectRatio = 1f
        givenImage()
        view.snapToCenter = true

        // Each step on its own is well inside the snap distance; together they leave it.
        dragBySteps(500f, 500f, (1..10).map { 500f + it * 4f to 500f })

        (view.currentEdits().crop.centerX() > 0.5f) shouldBeEqualTo true
    }

    @Test
    fun `the aspect tool reshapes the selected censor, not the crop`() {
        givenImage()
        view.tool = ImageEditorView.Tool.CENSOR
        drag(200f, 350f, 400f, 650f)

        view.isCensorSelected() shouldBeEqualTo true
        view.applySelectionAspectRatio(1f)

        val censor = view.currentEdits().censors.single()
        val ratio = (censor.width() * IMAGE_WIDTH) / (censor.height() * IMAGE_HEIGHT)
        (abs(ratio - 1f) < 0.001f) shouldBeEqualTo true
        view.currentEdits().crop.width() shouldBeEqualTo 1f
    }

    @Test
    fun `the aspect tool falls back to the crop when no censor is selected`() {
        givenImage()

        view.applySelectionAspectRatio(1f)

        (abs(croppedRatio() - 1f) < 0.001f) shouldBeEqualTo true
    }

    @Test
    fun `locking a ratio keeps the crop where it was framed`() {
        view.restoreEdits(ImageEditorEdits(crop = RectF(0f, 0f, 0.4f, 0.6f)))
        givenImage()

        view.applySelectionAspectRatio(1f)

        view.currentEdits().crop.run {
            (abs(centerX() - 0.2f) < 0.001f) shouldBeEqualTo true
            (abs(centerY() - 0.3f) < 0.001f) shouldBeEqualTo true
        }
        (abs(croppedRatio() - 1f) < 0.001f) shouldBeEqualTo true
    }

    @Test
    fun `the image's own ratio is offered reduced`() {
        givenImage()

        view.displayedAspectRatio() shouldBeEqualTo (2 to 1)
    }

    @Test
    fun `a crop can be dragged far below a twentieth of the image`() {
        givenImage()

        // The bottom right handle, pulled almost onto the opposite corner.
        drag(940f, 720f, 62f, 282f)

        view.currentEdits().crop.run {
            (width() < 0.02f) shouldBeEqualTo true
            (height() < 0.03f) shouldBeEqualTo true
            (width() > 0f) shouldBeEqualTo true
            (height() > 0f) shouldBeEqualTo true
        }
    }

    @Test
    fun `a locked ratio crop can also be dragged below a twentieth of the image`() {
        view.cropAspectRatio = 1f
        givenImage()

        // The locked crop's bottom right handle, pulled onto the corner it is anchored on.
        drag(720f, 720f, 285f, 285f)

        (abs(croppedRatio() - 1f) < 0.01f) shouldBeEqualTo true
        (view.currentEdits().crop.height() < 0.05f) shouldBeEqualTo true
    }

    @Test
    fun `without snapping a drawn censor edge stays where it was released`() {
        givenImage()
        view.tool = ImageEditorView.Tool.CENSOR

        drag(200f, 400f, VIEW_SIZE / 2f - 5f, 500f)

        (view.currentEdits().censors.single().right < 0.5f) shouldBeEqualTo true
    }
}
