/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar.effect

import kotlin.math.cos
import kotlin.math.sin

/**
 * A 3×3 rotation, mutated in place so a frame costs no allocation. Composition order matches the
 * reference renderer's, where each `rotateX/Y/Z` post-multiplies the model matrix.
 */
class Rot3 {

    var m00 = 1f; var m01 = 0f; var m02 = 0f
    var m10 = 0f; var m11 = 1f; var m12 = 0f
    var m20 = 0f; var m21 = 0f; var m22 = 1f

    fun identity() = apply {
        m00 = 1f; m01 = 0f; m02 = 0f
        m10 = 0f; m11 = 1f; m12 = 0f
        m20 = 0f; m21 = 0f; m22 = 1f
    }

    /**
     * A constant-speed turn about an arbitrary axis. Composing two axis rotations at the same rate
     * is a different motion: it speeds up and slows down, and on a symmetric solid it keeps landing
     * back on poses that look alike, which reads as snapping rather than tumbling.
     */
    fun rotateAxis(x: Float, y: Float, z: Float, angle: Float): Rot3 {
        val length = kotlin.math.sqrt(x * x + y * y + z * z)
        if (length == 0f) return this
        val ax = x / length
        val ay = y / length
        val az = z / length
        val c = cos(angle)
        val s = sin(angle)
        val t = 1f - c
        return post(
                t * ax * ax + c, t * ax * ay - s * az, t * ax * az + s * ay,
                t * ax * ay + s * az, t * ay * ay + c, t * ay * az - s * ax,
                t * ax * az - s * ay, t * ay * az + s * ax, t * az * az + c,
        )
    }

    fun rotateX(angle: Float) = post(
            1f, 0f, 0f,
            0f, cos(angle), -sin(angle),
            0f, sin(angle), cos(angle),
    )

    fun rotateY(angle: Float) = post(
            cos(angle), 0f, sin(angle),
            0f, 1f, 0f,
            -sin(angle), 0f, cos(angle),
    )

    fun rotateZ(angle: Float) = post(
            cos(angle), -sin(angle), 0f,
            sin(angle), cos(angle), 0f,
            0f, 0f, 1f,
    )

    private fun post(
            b00: Float, b01: Float, b02: Float,
            b10: Float, b11: Float, b12: Float,
            b20: Float, b21: Float, b22: Float,
    ) = apply {
        val a00 = m00; val a01 = m01; val a02 = m02
        val a10 = m10; val a11 = m11; val a12 = m12
        val a20 = m20; val a21 = m21; val a22 = m22
        m00 = a00 * b00 + a01 * b10 + a02 * b20
        m01 = a00 * b01 + a01 * b11 + a02 * b21
        m02 = a00 * b02 + a01 * b12 + a02 * b22
        m10 = a10 * b00 + a11 * b10 + a12 * b20
        m11 = a10 * b01 + a11 * b11 + a12 * b21
        m12 = a10 * b02 + a11 * b12 + a12 * b22
        m20 = a20 * b00 + a21 * b10 + a22 * b20
        m21 = a20 * b01 + a21 * b11 + a22 * b21
        m22 = a20 * b02 + a21 * b12 + a22 * b22
    }
}
