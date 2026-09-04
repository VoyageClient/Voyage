/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar.effect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The warp family, drawn through `drawBitmapMesh`: a uniform source grid plus the destination each
 * of its vertices moves to.
 *
 * The reference walks a grid of up to 80×80 cells and blits each one separately. Every one of these
 * displacements is smooth, so a coarser grid of interpolated quads is the same picture for a
 * fraction of the draw calls — the alternative is thousands of blits per avatar per frame.
 */
class WarpMesh {

    private var verts = FloatArray(0)
    private var grid = 0
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun draw(canvas: Canvas, effect: AvatarEffect, texture: Bitmap, sizePx: Int, phase: Float) {
        val n = gridFor(sizePx)
        if (grid != n) {
            grid = n
            verts = FloatArray((n + 1) * (n + 1) * 2)
        }
        val side = sideFraction(effect) * AvatarEffect.FIT_FRACTION * sizePx
        val origin = (sizePx - side) / 2f
        val turn = phase * TWO_PI

        // Lens' magnifier rides a figure eight; hoisted out of the vertex loop.
        val swing = sin(turn)
        val loop = 1f + swing * swing
        val lensX = 0.5f + LENS_ORBIT * cos(turn) / loop
        val lensY = 0.5f + LENS_ORBIT * swing * cos(turn) / loop
        val ring = phase * SHOCKWAVE_TRAVEL

        var i = 0
        for (r in 0..n) {
            val v = r / n.toFloat()
            for (c in 0..n) {
                val u = c / n.toFloat()
                var du = u
                var dv = v
                when (effect) {
                    AvatarEffect.WAVE_VERTICAL -> du = u + WAVE_AMPLITUDE * sin(TWO_PI * v * WAVE_CYCLES + turn)
                    AvatarEffect.WAVE_HORIZONTAL -> dv = v + WAVE_AMPLITUDE * cos(TWO_PI * u * WAVE_CYCLES + turn)
                    AvatarEffect.WOBBLE -> {
                        du = u + WOBBLE_AMPLITUDE * cos(TWO_PI * (phase + u))
                        dv = v + WOBBLE_AMPLITUDE * cos(TWO_PI * (phase + v))
                    }
                    AvatarEffect.SWIRL -> {
                        val dx = u - 0.5f
                        val dy = v - 0.5f
                        val d = sqrt(dx * dx + dy * dy)
                        if (d < SWIRL_RADIUS) {
                            val falloff = 1f - d / SWIRL_FALLOFF
                            val twist = SWIRL_STRENGTH * swing * falloff * falloff * falloff * falloff
                            val ct = cos(twist)
                            val st = sin(twist)
                            du = 0.5f + dx * ct - dy * st
                            dv = 0.5f + dx * st + dy * ct
                        }
                    }
                    AvatarEffect.LENS_DISTORT -> {
                        val dx = u - lensX
                        val dy = v - lensY
                        val d = sqrt(dx * dx + dy * dy)
                        if (d < LENS_RADIUS) {
                            val t = d / LENS_RADIUS
                            val magnify = 1f + (LENS_MAGNIFICATION - 1f) * (1f - t * t * (3f - 2f * t))
                            du = lensX + dx * magnify
                            dv = lensY + dy * magnify
                        }
                    }
                    AvatarEffect.SHOCKWAVE -> {
                        val dx = u - 0.5f
                        val dy = v - 0.5f
                        val d = sqrt(dx * dx + dy * dy)
                        val offset = abs(d - ring)
                        if (offset < SHOCKWAVE_BAND && d > 1e-4f) {
                            val push = sin((SHOCKWAVE_BAND - offset) / SHOCKWAVE_BAND * PI.toFloat()) * SHOCKWAVE_AMPLITUDE
                            du = u + dx / d * push
                            dv = v + dy / d * push
                        }
                    }
                    else -> Unit
                }
                verts[i++] = origin + du * side
                verts[i++] = origin + dv * side
            }
        }
        canvas.drawBitmapMesh(texture, n, n, verts, 0, null, 0, paint)
    }

    private fun sideFraction(effect: AvatarEffect) = when (effect) {
        AvatarEffect.WAVE_VERTICAL, AvatarEffect.WAVE_HORIZONTAL -> 1.49f
        AvatarEffect.WOBBLE, AvatarEffect.SWIRL -> 1.53f
        else -> 1.32f
    }

    private fun gridFor(sizePx: Int) = when {
        sizePx <= 64 -> 10
        sizePx <= 128 -> 16
        else -> 24
    }

    private companion object {
        const val TWO_PI = (2.0 * PI).toFloat()
        const val NOMINAL_IMAGE = 150f

        const val WAVE_AMPLITUDE = 15f / NOMINAL_IMAGE
        const val WAVE_CYCLES = NOMINAL_IMAGE / 250f
        const val WOBBLE_AMPLITUDE = 1f / 120f
        const val SWIRL_STRENGTH = 2f
        const val SWIRL_RADIUS = 35f / 125f
        const val SWIRL_FALLOFF = 50f / 125f
        const val LENS_MAGNIFICATION = 3f
        const val LENS_RADIUS = 16.38f / 65f
        const val LENS_ORBIT = 0.28f * 1.4142136f
        const val SHOCKWAVE_TRAVEL = 0.75f
        const val SHOCKWAVE_BAND = 0.07f
        const val SHOCKWAVE_AMPLITUDE = 4f / 80f
    }
}
