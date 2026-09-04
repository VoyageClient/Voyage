/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar.effect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import im.vector.app.features.home.avatar.effect.AvatarEffect.Companion.FIT_FRACTION
import im.vector.app.features.home.avatar.effect.AvatarEffect.Companion.PERIOD_FRAMES
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Draws one frame of an [AvatarEffect] into a software canvas.
 *
 * Geometry is ported from 3dgifmaker's p5/WEBGL renderer. Two conventions carry that port over:
 * lengths the reference expresses in source-image pixels are divided by [NOMINAL_IMAGE], and the
 * few places it mixes canvas units into image-scaled space are pinned to [REF_CANVAS_OVER_IMAGE] —
 * otherwise those effects would change proportions with the avatar's size instead of just its
 * resolution.
 */
class AvatarEffectPainter {

    private val solids = SolidRasterizer()
    private val spheres = SphereMapper()
    private val warps = WarpMesh()
    private val rotation = Rot3()
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun paint(canvas: Canvas, effect: AvatarEffect, texture: Bitmap, sizePx: Int, frame: Int) {
        val loopFrame = ((frame % PERIOD_FRAMES) + PERIOD_FRAMES) % PERIOD_FRAMES
        val p = loopFrame / PERIOD_FRAMES.toFloat()
        val turn = p * TWO_PI
        solids.begin(effect, loopFrame)
        when (effect) {
            AvatarEffect.SPIN_360 -> solids.draw(canvas, card, rotation.identity().rotateY(turn), texture, sizePx)
            // Unlike the 360, this one never shows the image backwards.
            AvatarEffect.SPIN_180 -> solids.draw(
                    canvas, card, rotation.identity().rotateY(turn), texture, sizePx,
                    mirrorU = turn >= PI.toFloat() / 2f && turn <= 1.5f * PI.toFloat(),
            )
            AvatarEffect.FRONT_FLIP -> solids.draw(canvas, card, rotation.identity().rotateX(-turn), texture, sizePx)
            AvatarEffect.RANDOM_ROTATIONS -> {
                val swing = sin(turn)
                solids.draw(
                        canvas, card,
                        rotation.identity()
                                .rotateX(swing * (PI.toFloat() / randomAxisDivisor(1) / 15f))
                                .rotateY(swing * (PI.toFloat() / randomAxisDivisor(101) / 15f))
                                .rotateZ(swing * (PI.toFloat() / randomAxisDivisor(201) / 15f)),
                        texture, sizePx,
                )
            }
            // A card laid back in perspective, lunging toward the camera twice a loop.
            AvatarEffect.ZOOM_TILTED -> solids.draw(
                    canvas, card, rotation.identity().rotateX(1f), texture, sizePx,
                    pushZ = abs(sin(turn)) * FIT_FRACTION,
            )
            AvatarEffect.SPIN_360_THICK ->
                solids.draw(canvas, slab, rotation.identity().rotateY(turn), texture, sizePx)

            AvatarEffect.CUBE -> solids.draw(canvas, cube, rotation.identity().rotateY(turn), texture, sizePx)
            AvatarEffect.CUBE_WOBBLY -> solids.draw(
                    canvas, cube, rotation.identity().rotateY(turn).rotateX(cos(p * 4f * PI.toFloat())), texture, sizePx
            )
            AvatarEffect.CUBE_DIAGONAL ->
                solids.draw(canvas, cube, rotation.identity().rotateY(turn).rotateZ(turn), texture, sizePx)
            AvatarEffect.DODECAHEDRON ->
                solids.draw(canvas, dodecahedron, rotation.identity().rotateY(turn).rotateZ(turn), texture, sizePx)
            // A tetrahedron's symmetry axes are its four vertices and the three coordinate axes, so
            // the cube's compound turn keeps returning it to poses that look identical. One steady
            // turn about an axis it has no symmetry around tumbles it through all four faces evenly.
            AvatarEffect.TETRAHEDRON -> solids.draw(
                    canvas, tetrahedron,
                    // The quarter turn is applied to the tumble rather than inside it, so it stands
                    // the whole solid up rather than spinning the picture on its faces.
                    rotation.identity()
                            .rotateZ(TETRAHEDRON_TURN)
                            .rotateAxis(TUMBLE_AXIS_X, TUMBLE_AXIS_Y, TUMBLE_AXIS_Z, turn),
                    texture, sizePx,
            )
            AvatarEffect.PHOTO_CUBE -> photoCube(canvas, texture, sizePx, p)
            AvatarEffect.PYRAMID ->
                solids.draw(canvas, pyramid, rotation.identity().rotateY(turn), texture, sizePx)
            AvatarEffect.DONUT ->
                solids.draw(canvas, torus(sizePx), rotation.identity().rotateY(turn), texture, sizePx)
            AvatarEffect.SPHERE_LOW_POLY -> solids.draw(
                    canvas, lowPolySphere, rotation.identity().rotateY(PI.toFloat() + turn), texture, sizePx
            )
            AvatarEffect.SPHERE ->
                spheres.draw(canvas, texture, sizePx, SPHERE_RADIUS, PI.toFloat() + turn, insideOut = false)
            AvatarEffect.SPHERE_INSIDE ->
                spheres.draw(canvas, texture, sizePx, INSIDE_SPHERE_RADIUS, sin(p * 4f * PI.toFloat()), insideOut = true)
            // The only sphere whose radius moves, so it is also the only one that cannot keep one
            // mapping for the whole loop; it is quantised so the loop reuses a handful of them.
            AvatarEffect.BALLOON -> spheres.draw(
                    canvas, texture, sizePx, quantizedRadius(p), PI.toFloat(), insideOut = false
            )

            AvatarEffect.WAVE_VERTICAL,
            AvatarEffect.WAVE_HORIZONTAL,
            AvatarEffect.WOBBLE,
            AvatarEffect.SWIRL,
            AvatarEffect.LENS_DISTORT,
            AvatarEffect.SHOCKWAVE -> warps.draw(canvas, effect, texture, sizePx, p)

            AvatarEffect.SPIN_CW -> flat(canvas, texture, sizePx, FIT_FRACTION) { it.postRotate(degrees(turn)) }
            AvatarEffect.SPIN_CCW -> flat(canvas, texture, sizePx, FIT_FRACTION) { it.postRotate(-degrees(turn)) }
            AvatarEffect.ROCKING -> flat(canvas, texture, sizePx, FIT_FRACTION) {
                it.postRotate(ROCKING_DEGREES * sin(turn))
            }
            AvatarEffect.HEARTBEAT -> flat(canvas, texture, sizePx, 1.32f * FIT_FRACTION * (1f + 0.2f * sin(turn)))
            AvatarEffect.ZOOM -> flat(canvas, texture, sizePx, (1f - cos(turn)) * FIT_FRACTION * 0.8f)
            AvatarEffect.SQUISHY -> {
                // The only effect drawn straight in canvas units, with no fit scaling.
                val w = (1f / 1.5f + 0.25f * sin(turn)) * sizePx
                val h = (1f / 1.5f + 0.25f * cos(turn)) * sizePx
                matrix.reset()
                matrix.setRectToRect(
                        android.graphics.RectF(0f, 0f, texture.width.toFloat(), texture.height.toFloat()),
                        android.graphics.RectF((sizePx - w) / 2f, (sizePx - h) / 2f, (sizePx + w) / 2f, (sizePx + h) / 2f),
                        Matrix.ScaleToFit.FILL,
                )
                canvas.drawBitmap(texture, matrix, paint)
            }
            AvatarEffect.FLOAT -> flat(canvas, texture, sizePx, FIT_FRACTION) {
                it.postTranslate(0f, FLOAT_TRAVEL * sin(turn) * sizePx)
            }
            AvatarEffect.FIGURE_EIGHT -> flat(canvas, texture, sizePx, FIGURE_EIGHT_FIT) {
                it.postTranslate(FIGURE_EIGHT_RADIUS * sin(turn) * sizePx, FIGURE_EIGHT_RADIUS * sin(turn) * cos(turn) * sizePx)
            }
            AvatarEffect.TREMBLE -> flat(canvas, texture, sizePx, 1.5f * FIT_FRACTION) {
                it.postTranslate(
                        TREMBLE_TRAVEL * jitter(frame * 2) * sizePx,
                        TREMBLE_TRAVEL * jitter(frame * 2 + 1) * sizePx,
                )
            }
            AvatarEffect.DVD_BOUNCE -> {
                val side = 0.5f * FIT_FRACTION
                val travelX = 1f - side
                val travelY = 1f - side
                val x = pingPong(p * 2f * travelX * DVD_X_BOUNCES, travelX)
                val y = pingPong(p * 2f * travelY * DVD_Y_BOUNCES, travelY)
                flat(canvas, texture, sizePx, side) {
                    it.postTranslate((x - travelX / 2f) * sizePx, (y - travelY / 2f) * sizePx)
                }
            }
            AvatarEffect.BLINK -> if (frame % 10 > 5) flat(canvas, texture, sizePx, FIT_FRACTION)
        }
    }

    /** Draws the texture centred, scaled to [sideFraction] of the canvas, under an extra transform. */
    private inline fun flat(canvas: Canvas, texture: Bitmap, sizePx: Int, sideFraction: Float, extra: (Matrix) -> Unit = {}) {
        if (sideFraction <= 0f) return
        val side = sideFraction * sizePx
        matrix.reset()
        matrix.setScale(side / texture.width, side / texture.height)
        matrix.postTranslate(-side / 2f, -side / 2f)
        extra(matrix)
        matrix.postTranslate(sizePx / 2f, sizePx / 2f)
        canvas.drawBitmap(texture, matrix, paint)
    }

    // A balloon growing smoothly would rebuild the sphere's pixel mapping on every frame, which is
    // the whole cost this avoids; in steps it reuses each one for several frames instead.
    private fun quantizedRadius(phase: Float) =
            ((phase * BALLOON_STEPS).toInt() + 1) / BALLOON_STEPS.toFloat()

    private fun photoCube(canvas: Canvas, texture: Bitmap, sizePx: Int, p: Float) {
        // One quarter-turn per loop: it dwells for the first 30%, then eases through the turn while
        // tipping, rising and pulling back, and lands flat on the next face.
        val eased = if (p < 0.3f) 0f else ((p - 0.3f) / 0.7f).let { it * it * (3f - 2f * it) }
        val lift = sin(eased * PI.toFloat())
        val yaw = eased * (PI.toFloat() / 2f)
        // The resting scale is the one that fits a cube face to the frame at this canvas size.
        val faceWidth = 1f - 4f / sizePx
        val resting = faceWidth * SolidRasterizer.EYE_Z / (SolidRasterizer.EYE_Z + faceWidth / 2f) / 0.5f
        val scale = resting - (resting - PHOTO_CUBE_ZOOM_OUT) * lift
        solids.draw(
                canvas, cube, rotation.identity().rotateX(0.14f * lift).rotateY(yaw), texture, sizePx,
                scale = scale, offsetY = -lift * 0.05f,
        )
    }

    // The reference seeds three axis divisors off the slider, a hundred apart, through the fractional
    // part of a scaled sine; with its default seed they come out as an unequal sway on all three axes.
    private fun randomAxisDivisor(seed: Int): Float {
        val x = 10000.0 * kotlin.math.sin(seed.toDouble())
        return (x - kotlin.math.floor(x)).toFloat()
    }

    private fun jitter(seed: Int): Float {
        val x = sin(seed * 12.9898f) * 43758.545f
        return (x - floor(x)) * 2f - 1f
    }

    private fun pingPong(value: Float, span: Float): Float {
        if (span <= 0f) return 0f
        val cycle = 2f * span
        val t = ((value % cycle) + cycle) % cycle
        return if (t <= span) t else cycle - t
    }

    private fun degrees(radians: Float) = radians * 180f / PI.toFloat()

    private val card by lazy { MeshLibrary.card() }
    private val cube by lazy { MeshLibrary.cube(0.25f) }
    private val slab by lazy { MeshLibrary.slab(depth = SLAB_THICKNESS) }
    private val pyramid by lazy { MeshLibrary.pyramid(0.55f) }
    private val dodecahedron by lazy { MeshLibrary.dodecahedron(0.40f) }
    private val tetrahedron by lazy { MeshLibrary.tetrahedron(0.45f) }
    private val lowPolySphere by lazy { MeshLibrary.sphere(SPHERE_RADIUS, 6, 6) }
    private var torusMesh: Mesh3d? = null
    private var torusDetail = 0

    private fun torus(sizePx: Int): Mesh3d {
        // Every visible quad is a clip plus a blit, and a torus has more of them than anything else
        // here, so the detail comes down to what actually reads at the size being drawn.
        val detail = if (sizePx <= 192) 16 else 24
        if (torusMesh == null || torusDetail != detail) {
            torusMesh = MeshLibrary.torus(0.2f, 0.1f, detail, detail * 2 / 3)
            torusDetail = detail
        }
        return torusMesh!!
    }

    private companion object {
        const val TWO_PI = (2.0 * PI).toFloat()

        /** The source image the reference's own defaults are expressed against. */
        const val NOMINAL_IMAGE = 150f

        /** Its canvas-to-image ratio, for the effects whose geometry mixes the two. */
        const val REF_CANVAS_OVER_IMAGE = 250f / NOMINAL_IMAGE

        const val SPHERE_RADIUS = 1f / 3f
        const val INSIDE_SPHERE_RADIUS = 2.4f
        const val SLAB_THICKNESS = 20f / NOMINAL_IMAGE * FIT_FRACTION

        const val ROCKING_DEGREES = 80f
        const val FLOAT_TRAVEL = 30f / NOMINAL_IMAGE * FIT_FRACTION
        const val TREMBLE_TRAVEL = 10f / NOMINAL_IMAGE * 1.5f * FIT_FRACTION
        const val FIGURE_EIGHT_FIT = 0.37f
        const val FIGURE_EIGHT_RADIUS = 0.5f * REF_CANVAS_OVER_IMAGE * FIGURE_EIGHT_FIT
        const val DVD_X_BOUNCES = 3f
        const val DVD_Y_BOUNCES = 4f

        const val PHOTO_CUBE_ZOOM_OUT = 0.62f
        const val BALLOON_STEPS = 20f

        /** Screen y runs downwards, so a negative turn is anticlockwise. */
        const val TETRAHEDRON_TURN = -(PI / 2.0).toFloat()

        // Deliberately not a vertex direction (±1,±1,±1) or a coordinate axis.
        const val TUMBLE_AXIS_X = 1f
        const val TUMBLE_AXIS_Y = 0.42f
        const val TUMBLE_AXIS_Z = 0.19f
    }
}
