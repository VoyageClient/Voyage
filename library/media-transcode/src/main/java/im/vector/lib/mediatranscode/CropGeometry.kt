/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode

import kotlin.math.roundToInt

/**
 * The maths behind the GL export stage, kept free of Android types so it can be tested directly.
 *
 * Crop rectangles are normalised (0..1) against the *displayed* frame — the decoder's output turned
 * clockwise by the total rotation — with y running downwards.
 */
internal object CropGeometry {

    /** Encoders before API 21 emit green bands, or refuse to configure, on odd-sized frames. */
    const val ALIGNMENT = 16
    const val MIN_DIMENSION = 128f

    data class Output(
            val width: Int,
            val height: Int,
            val crop: FloatArray,
    ) {
        // FloatArray gives the generated data-class equals reference semantics; tests compare values.
        override fun equals(other: Any?) = other is Output &&
                width == other.width && height == other.height && crop.contentEquals(other.crop)

        override fun hashCode() = (width * 31 + height) * 31 + crop.contentHashCode()
    }

    /**
     * Output size for [crop] of a [displayWidth] x [displayHeight] frame, plus the crop to really
     * use. Alignment rounds the size down by up to 15 pixels on each axis independently, which on a
     * small crop is a visible stretch, so the rectangle is narrowed to match what is encoded.
     *
     * @param targetWidth,targetHeight explicit output size; only honoured when both are given.
     */
    fun outputFor(
            displayWidth: Int,
            displayHeight: Int,
            crop: FloatArray,
            targetWidth: Int? = null,
            targetHeight: Int? = null,
    ): Output {
        val cropWidth = (crop[2] - crop[0]) * displayWidth
        val cropHeight = (crop[3] - crop[1]) * displayHeight
        if (targetWidth != null && targetHeight != null) {
            return Output(align(targetWidth, displayWidth), align(targetHeight, displayHeight), crop)
        }
        if (cropWidth <= 0f || cropHeight <= 0f) return Output(align(displayWidth, displayWidth), align(displayHeight, displayHeight), crop)
        // Both axes are lifted off the floor by the same factor, or a long thin crop would come out
        // squashed; never past the source, since upscaling only inflates the file.
        val lift = maxOf(1f, MIN_DIMENSION / cropWidth, MIN_DIMENSION / cropHeight)
                .coerceAtMost(maxOf(1f, minOf(displayWidth / cropWidth, displayHeight / cropHeight)))
        val width = align((cropWidth * lift).roundToInt(), displayWidth)
        val height = align((cropHeight * lift).roundToInt(), displayHeight)
        return Output(width, height, fitToAspect(crop, cropWidth, cropHeight, width.toFloat() / height))
    }

    /** Shrinks [crop] about its centre until it has [aspect], so nothing is stretched. */
    private fun fitToAspect(crop: FloatArray, cropWidth: Float, cropHeight: Float, aspect: Float): FloatArray {
        if (cropWidth <= 0f || cropHeight <= 0f) return crop
        val current = cropWidth / cropHeight
        val scaleX = if (current > aspect) aspect / current else 1f
        val scaleY = if (current > aspect) 1f else current / aspect
        val halfWidth = (crop[2] - crop[0]) / 2f * scaleX
        val halfHeight = (crop[3] - crop[1]) / 2f * scaleY
        val centreX = (crop[0] + crop[2]) / 2f
        val centreY = (crop[1] + crop[3]) / 2f
        return floatArrayOf(centreX - halfWidth, centreY - halfHeight, centreX + halfWidth, centreY + halfHeight)
    }

    fun align(value: Int, ceiling: Int): Int {
        val alignedCeiling = (ceiling / ALIGNMENT * ALIGNMENT).coerceAtLeast(ALIGNMENT)
        return (value / ALIGNMENT * ALIGNMENT).coerceIn(ALIGNMENT, alignedCeiling)
    }

    /**
     * The four quad corners as source-texture coordinates, in the order bottom-left, bottom-right,
     * top-left, top-right and in GL's convention with t running upwards. Rotation is inverted here
     * rather than baked into a matrix, so the encoded frame is already the right way up and the mp4
     * needs no orientation hint.
     */
    fun textureCoords(crop: FloatArray, rotationDegrees: Int): FloatArray {
        val corners = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        val result = FloatArray(corners.size)
        for (index in 0 until corners.size / 2) {
            val u = crop[0] + corners[index * 2] * (crop[2] - crop[0])
            val v = crop[1] + corners[index * 2 + 1] * (crop[3] - crop[1])
            val coded = unrotate(u, v, rotationDegrees)
            result[index * 2] = coded[0]
            result[index * 2 + 1] = 1f - coded[1]
        }
        return result
    }

    /** Maps a point of the displayed frame back onto the frame the decoder emits, both y-down. */
    fun unrotate(u: Float, v: Float, rotationDegrees: Int): FloatArray {
        return when (((rotationDegrees % 360) + 360) % 360) {
            90 -> floatArrayOf(v, 1f - u)
            180 -> floatArrayOf(1f - u, 1f - v)
            270 -> floatArrayOf(1f - v, u)
            else -> floatArrayOf(u, v)
        }
    }
}
