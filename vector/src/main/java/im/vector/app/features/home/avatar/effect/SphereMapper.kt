/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar.effect

import android.graphics.Bitmap
import android.graphics.Canvas
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Draws a texture-wrapped sphere by intersecting a ray per pixel, rather than through
 * [SolidRasterizer].
 *
 * A sphere at the reference's detail is ~380 quads, and a clip plus a `drawBitmap` each would be by
 * far the most expensive thing this feature draws. One ray per covered pixel is cheaper at avatar
 * sizes, and comes out smooth instead of faceted.
 *
 * Every sphere here turns about the vertical axis, and turning a sphere that way only moves the
 * texture around it in longitude. So which texel each pixel lands on is worked out once and reused,
 * and a frame is that table plus an offset: no trigonometry per pixel, per frame, per avatar.
 */
class SphereMapper {

    private var pixels = IntArray(0)
    private var surface: Bitmap? = null

    private var texels = IntArray(0)
    private var texWidth = 0
    private var texHeight = 0
    private var texturedBitmap: Bitmap? = null

    /** Per pixel: the texel row it lands on (already multiplied out), and its column at zero turn. */
    private var mappedRow = IntArray(0)
    private var mappedColumn = IntArray(0)
    private var mappedSize = 0
    private var mappedRadius = 0f
    private var mappedInsideOut = false
    private var mappedTexWidth = 0
    private var mappedTexHeight = 0

    fun draw(canvas: Canvas, texture: Bitmap, sizePx: Int, radius: Float, yaw: Float, insideOut: Boolean) {
        if (radius <= 0f) return
        cacheTexels(texture)
        val target = surfaceFor(sizePx)
        buildMappingIfNeeded(sizePx, radius, insideOut)

        // Turning the sphere moves the texture around it, which is a whole number of texels.
        var shift = (-yaw / TWO_PI * texWidth).roundToInt() % texWidth
        if (shift < 0) shift += texWidth

        for (i in 0 until sizePx * sizePx) {
            val row = mappedRow[i]
            if (row < 0) {
                pixels[i] = 0
                continue
            }
            var column = mappedColumn[i] + shift
            if (column >= texWidth) column -= texWidth
            pixels[i] = texels[row + column]
        }
        target.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        canvas.drawBitmap(target, 0f, 0f, null)
    }

    private fun buildMappingIfNeeded(sizePx: Int, radius: Float, insideOut: Boolean) {
        val unchanged = mappedSize == sizePx && mappedRadius == radius && mappedInsideOut == insideOut &&
                mappedTexWidth == texWidth && mappedTexHeight == texHeight
        if (unchanged) return

        val count = sizePx * sizePx
        if (mappedRow.size < count) {
            mappedRow = IntArray(count)
            mappedColumn = IntArray(count)
        }
        val half = sizePx / 2f
        val eye = SolidRasterizer.EYE_Z
        // |eye + t·d|² = r², with d = (u/eye, v/eye, -1).
        val c = eye * eye - radius * radius

        var i = 0
        for (py in 0 until sizePx) {
            val v = (py + 0.5f - half) / sizePx
            for (px in 0 until sizePx) {
                val u = (px + 0.5f - half) / sizePx
                val dx = u / eye
                val dy = v / eye
                val a = dx * dx + dy * dy + 1f
                val b = -2f * eye
                val disc = b * b - 4f * a * c
                if (disc < 0f) {
                    mappedRow[i++] = -1
                    continue
                }
                val sq = sqrt(disc)
                val near = (-b - sq) / (2f * a)
                val far = (-b + sq) / (2f * a)
                // Once the sphere has grown past the camera the near hit is behind it, and what is
                // actually in view is the inside of the far wall.
                val t = if (insideOut || near <= 0f) far else near
                if (t <= 0f) {
                    mappedRow[i++] = -1
                    continue
                }
                val x = t * dx
                val y = t * dy
                val z = eye - t

                val phi = acos((-y / radius).coerceIn(-1f, 1f))
                var theta = atan2(x, z)
                if (theta < 0f) theta += TWO_PI
                val column = (theta / TWO_PI * texWidth).toInt().coerceIn(0, texWidth - 1)
                val row = (phi / PI.toFloat() * texHeight).toInt().coerceIn(0, texHeight - 1)
                mappedColumn[i] = column
                mappedRow[i] = row * texWidth
                i++
            }
        }
        mappedSize = sizePx
        mappedRadius = radius
        mappedInsideOut = insideOut
        mappedTexWidth = texWidth
        mappedTexHeight = texHeight
    }

    private fun surfaceFor(sizePx: Int): Bitmap {
        val existing = surface
        if (existing != null && existing.width == sizePx) return existing
        if (pixels.size < sizePx * sizePx) pixels = IntArray(sizePx * sizePx)
        return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { surface = it }
    }

    private fun cacheTexels(texture: Bitmap) {
        if (texturedBitmap === texture && texels.isNotEmpty()) return
        texWidth = texture.width
        texHeight = texture.height
        if (texels.size < texWidth * texHeight) texels = IntArray(texWidth * texHeight)
        texture.getPixels(texels, 0, texWidth, 0, 0, texWidth, texHeight)
        texturedBitmap = texture
    }

    private companion object {
        const val TWO_PI = (2.0 * PI).toFloat()
    }
}
