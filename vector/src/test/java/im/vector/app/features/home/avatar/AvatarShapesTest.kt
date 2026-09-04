/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar

import android.graphics.Path
import android.graphics.RectF
import im.vector.app.features.settings.AvatarShape
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarShapesTest {

    private val bounds = RectF(0f, 0f, 100f, 100f)

    @Test
    fun `every shape fills its bounds without escaping them`() {
        for (shape in AvatarShape.values()) {
            val measured = RectF()
            AvatarShapes.path(shape, bounds).computeBounds(measured, true)

            withClue(shape) { measured.isEmpty.shouldBeFalse() }
            withClue(shape) { (measured.left >= -TOLERANCE && measured.top >= -TOLERANCE).shouldBeTrue() }
            withClue(shape) {
                (measured.right <= bounds.right + TOLERANCE && measured.bottom <= bounds.bottom + TOLERANCE).shouldBeTrue()
            }
            // Polygons keep their proportions, so only the dimension they are limited by fills the
            // bounds — but one of them always does, or the shape would be needlessly small.
            withClue(shape) {
                (measured.width() >= bounds.width() - TOLERANCE || measured.height() >= bounds.height() - TOLERANCE)
                        .shouldBeTrue()
            }
        }
    }

    @Test
    fun `polygons have the vertex count their name promises`() {
        mapOf(
                AvatarShape.TRIANGLE to 3,
                AvatarShape.RHOMBUS to 4,
                AvatarShape.PENTAGON to 5,
                AvatarShape.HEXAGON to 6,
                AvatarShape.HEPTAGON to 7,
                AvatarShape.OCTAGON to 8,
                AvatarShape.NONAGON to 9,
                AvatarShape.DECAGON to 10,
        ).forEach { (shape, sides) ->
            withClue(shape) { corners(AvatarShapes.path(shape, bounds)) shouldBeEqualTo sides }
        }
    }

    @Test
    fun `an animated shape falls back to the square it is rendered into`() {
        val square = RectF()
        AvatarShapes.path(AvatarShape.CUBE_DIAGONAL, bounds).computeBounds(square, true)

        square shouldBeEqualTo bounds
    }

    @Test
    fun `storage keys are unique and stable for the shapes that predate the rest`() {
        val keys = AvatarShape.values().map { it.storageKey }
        keys.distinct().size shouldBeEqualTo keys.size

        AvatarShape.of("circle") shouldBeEqualTo AvatarShape.CIRCLE
        AvatarShape.of("rounded") shouldBeEqualTo AvatarShape.ROUNDED
        AvatarShape.of("square") shouldBeEqualTo AvatarShape.SQUARE
        AvatarShape.of("something we never shipped") shouldBeEqualTo AvatarShape.CIRCLE
        AvatarShape.of(null) shouldBeEqualTo AvatarShape.CIRCLE
    }

    /** Counts direction changes around the outline, which for a convex polygon is its vertex count. */
    private fun corners(path: Path): Int {
        val measure = android.graphics.PathMeasure(path, false)
        val length = measure.length
        val position = FloatArray(2)
        val tangent = FloatArray(2)
        var previousAngle = Float.NaN
        var firstAngle = Float.NaN
        var corners = 0
        val steps = 720
        for (i in 0..steps) {
            measure.getPosTan(length * i / steps, position, tangent)
            val angle = kotlin.math.atan2(tangent[1], tangent[0])
            if (previousAngle.isNaN()) firstAngle = angle else if (turns(previousAngle, angle)) corners++
            previousAngle = angle
        }
        // The outline is closed, so the vertex the walk started on is only seen on the way back round.
        if (turns(previousAngle, firstAngle)) corners++
        return corners
    }

    private fun turns(from: Float, to: Float): Boolean {
        var delta = kotlin.math.abs(to - from)
        if (delta > Math.PI) delta = (2 * Math.PI - delta).toFloat()
        return delta > 0.2f
    }

    private fun <T> withClue(clue: Any, block: () -> T): T = try {
        block()
    } catch (error: AssertionError) {
        throw AssertionError("$clue: ${error.message}", error)
    }

    private companion object {
        const val TOLERANCE = 0.5f
    }
}
