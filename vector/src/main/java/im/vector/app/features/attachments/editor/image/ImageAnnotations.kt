/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.image

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlin.math.min

/**
 * Annotations are normalised against the *unrotated* image, so they stay on what they cover through
 * any turn or tilt, and are drawn onto the source before the geometry is applied.
 */
@Parcelize
data class CensorEdit(val rect: RectF, val color: Int = Color.BLACK) : Parcelable

/**
 * @property points x, y pairs normalised against the unrotated image.
 * @property width stroke width as a fraction of the image's shorter side, so it scales with it.
 */
@Parcelize
data class BrushStroke(val points: FloatArray, val color: Int, val width: Float) : Parcelable {

    // FloatArray gives the generated equals reference semantics, which undo compares states with.
    override fun equals(other: Any?) = other is BrushStroke &&
            color == other.color && width == other.width && points.contentEquals(other.points)

    override fun hashCode() = (points.contentHashCode() * 31 + color) * 31 + width.hashCode()

    /** A smoothed path through the points, in the pixels of a [imageWidth] x [imageHeight] image. */
    fun toPath(imageWidth: Float, imageHeight: Float): Path = Path().apply {
        if (points.size < 2) return@apply
        var lastX = points[0] * imageWidth
        var lastY = points[1] * imageHeight
        moveTo(lastX, lastY)
        if (points.size == 2) {
            // A tap: a zero-length segment is what a round cap turns into a dot.
            lineTo(lastX, lastY)
            return@apply
        }
        var index = 2
        while (index < points.size) {
            val x = points[index] * imageWidth
            val y = points[index + 1] * imageHeight
            quadTo(lastX, lastY, (lastX + x) / 2f, (lastY + y) / 2f)
            lastX = x
            lastY = y
            index += 2
        }
        lineTo(lastX, lastY)
    }

    fun widthIn(imageWidth: Float, imageHeight: Float) = width * min(imageWidth, imageHeight)
}

object ImageAnnotationPainter {

    fun strokePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Draws censors, then strokes, over an unrotated [width] x [height] image at the canvas origin. */
    fun draw(canvas: Canvas, width: Float, height: Float, censors: List<CensorEdit>, strokes: List<BrushStroke>) {
        val fill = Paint()
        censors.forEach {
            fill.color = it.color
            canvas.drawRect(it.rect.left * width, it.rect.top * height, it.rect.right * width, it.rect.bottom * height, fill)
        }
        val paint = strokePaint()
        strokes.forEach {
            paint.color = it.color
            paint.strokeWidth = it.widthIn(width, height)
            canvas.drawPath(it.toPath(width, height), paint)
        }
    }
}
