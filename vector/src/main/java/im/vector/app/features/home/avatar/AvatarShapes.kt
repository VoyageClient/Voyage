/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar

import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.settings.AvatarShape
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object AvatarShapes {

    private val badgeRadii by lazy {
        AvatarShape.STATIC.associateWith { shape ->
            val extent = 2048
            val region = Region().apply {
                setPath(path(shape, RectF(0f, 0f, extent.toFloat(), extent.toFloat())), Region(0, 0, extent, extent))
            }
            var inside = extent / 2
            var outside = extent
            while (outside - inside > 1) {
                val middle = (inside + outside) / 2
                if (region.contains(middle, middle)) inside = middle else outside = middle
            }
            (outside.toFloat() / extent - 0.5f) * sqrt(2f)
        }
    }

    // Keep badges stationary while animated avatars move.
    fun badgeRadiusFraction(shape: AvatarShape): Float = if (shape.isAnimated) 0.5f else badgeRadii.getValue(shape)

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
            // Animated shapes have no fixed outline.
            else -> into.addRect(bounds, Path.Direction.CW)
        }
        return into
    }

    // Reduce the width so oval avatars differ visibly from circles.
    private fun ovalBounds(bounds: RectF): RectF {
        val inset = bounds.width() * (1f - OVAL_WIDTH_FRACTION) / 2f
        return RectF(bounds.left + inset, bounds.top, bounds.right - inset, bounds.bottom)
    }

    private fun semicircle(bounds: RectF, into: Path) {
        val full = RectF(bounds.left, bounds.top, bounds.right, bounds.top + bounds.height() * 2f)
        into.addArc(full, 180f, 180f)
        into.close()
    }

    // Fit the polygon's bounding box without stretching its proportions.
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
