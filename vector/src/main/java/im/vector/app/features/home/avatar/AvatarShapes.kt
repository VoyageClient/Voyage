/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar

import android.graphics.Path
import android.graphics.RectF
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.settings.AvatarShape
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The outline every avatar is drawn in, as a [Path], for whichever shape the user picked. */
object AvatarShapes {

    fun path(shape: AvatarShape, bounds: RectF, into: Path = Path()): Path {
        into.reset()
        when (shape) {
            AvatarShape.CIRCLE -> into.addOval(bounds, Path.Direction.CW)
            AvatarShape.ROUNDED -> {
                val radius = minOf(bounds.width(), bounds.height()) * AvatarRenderer.ROUNDED_CORNER_PERCENT
                into.addRoundRect(bounds, radius, radius, Path.Direction.CW)
            }
            AvatarShape.SQUARE -> into.addRect(bounds, Path.Direction.CW)
            AvatarShape.OVAL -> into.addOval(ovalBounds(bounds), Path.Direction.CW)
            AvatarShape.SEMICIRCLE -> semicircle(bounds, into)
            AvatarShape.RHOMBUS -> polygon(bounds, into, 4, ROTATION_POINT_UP)
            AvatarShape.TRIANGLE -> polygon(bounds, into, 3, ROTATION_POINT_UP)
            AvatarShape.PENTAGON -> polygon(bounds, into, 5, ROTATION_POINT_UP)
            AvatarShape.HEXAGON -> polygon(bounds, into, 6, ROTATION_POINT_UP)
            AvatarShape.HEPTAGON -> polygon(bounds, into, 7, ROTATION_POINT_UP)
            AvatarShape.OCTAGON -> polygon(bounds, into, 8, ROTATION_POINT_UP)
            AvatarShape.NONAGON -> polygon(bounds, into, 9, ROTATION_POINT_UP)
            AvatarShape.DECAGON -> polygon(bounds, into, 10, ROTATION_POINT_UP)
            // An animated shape carries its own silhouette; a caller that still needs an outline
            // (a stroke ring, a preview cell) gets the square the frame is rendered into.
            else -> into.addRect(bounds, Path.Direction.CW)
        }
        return into
    }

    // Narrower than the bounds, or in the square an avatar view always is it would be a circle.
    private fun ovalBounds(bounds: RectF): RectF {
        val inset = bounds.width() * (1f - OVAL_WIDTH_FRACTION) / 2f
        return RectF(bounds.left + inset, bounds.top, bounds.right - inset, bounds.bottom)
    }

    private fun semicircle(bounds: RectF, into: Path) {
        val full = RectF(bounds.left, bounds.top, bounds.right, bounds.top + bounds.height() * 2f)
        into.addArc(full, 180f, 180f)
        into.close()
    }

    /**
     * A regular [sides]-gon, scaled so its own bounding box fits [bounds] rather than the circle it
     * is inscribed in — a triangle inscribed in the avatar's circle reads as much smaller than the
     * other shapes at the same view size.
     *
     * The scale is uniform so the polygon stays regular. A hexagon's box is a sixth narrower than
     * it is tall, and stretching it to fill a square deforms it visibly.
     */
    private fun polygon(bounds: RectF, into: Path, sides: Int, rotationRad: Float) {
        val step = 2.0 * PI / sides
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val xs = FloatArray(sides)
        val ys = FloatArray(sides)
        for (i in 0 until sides) {
            val angle = rotationRad + i * step
            val x = cos(angle).toFloat()
            val y = sin(angle).toFloat()
            xs[i] = x
            ys[i] = y
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val scale = minOf(bounds.width() / (maxX - minX), bounds.height() / (maxY - minY))
        val insetX = (bounds.width() - (maxX - minX) * scale) / 2f
        val insetY = (bounds.height() - (maxY - minY) * scale) / 2f
        for (i in 0 until sides) {
            val x = bounds.left + insetX + (xs[i] - minX) * scale
            val y = bounds.top + insetY + (ys[i] - minY) * scale
            if (i == 0) into.moveTo(x, y) else into.lineTo(x, y)
        }
        into.close()
    }

    private const val OVAL_WIDTH_FRACTION = 0.72f
    private val ROTATION_POINT_UP = (-PI / 2.0).toFloat()
}
