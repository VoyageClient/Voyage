/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar.effect

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.sqrt

/**
 * Checks each effect's declared [AvatarEffect.reach] against what it actually draws.
 *
 * The declared value has to be an upper bound, since it decides how far an avatar is inset to keep
 * it inside a circle: too small and the shape is clipped, too large and it is needlessly shrunk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarEffectReachTest {

    @Test
    fun `every effect declares a reach that covers what it draws, without much to spare`() {
        val texture = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val wrong = mutableListOf<String>()

        for (effect in AvatarEffect.values()) {
            val measured = measureReach(effect, texture)
            when {
                effect.reach < measured -> wrong += "%s draws out to %.2f but declares %.2f".format(effect, measured, effect.reach)
                effect.reach > measured + SLACK ->
                    wrong += "%s only draws out to %.2f but declares %.2f, so it is inset further than it needs".format(
                            effect, measured, effect.reach
                    )
            }
        }

        if (wrong.isNotEmpty()) throw AssertionError(wrong.joinToString("\n"))
    }

    /** How far the effect draws from the centre, as a multiple of half the canvas. */
    private fun measureReach(effect: AvatarEffect, texture: Bitmap): Float {
        val half = SIZE / 2f
        val pixels = IntArray(SIZE * SIZE)
        var furthest = 0f
        for (frame in 0 until AvatarEffect.PERIOD_FRAMES) {
            AvatarEffectRenderer.renderStill(effect, texture, SIZE, frame).getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            for (y in 0 until SIZE) {
                for (x in 0 until SIZE) {
                    if (Color.alpha(pixels[y * SIZE + x]) < COVERED) continue
                    val dx = (x + 0.5f - half) / half
                    val dy = (y + 0.5f - half) / half
                    val distance = sqrt(dx * dx + dy * dy)
                    if (distance > furthest) furthest = distance
                }
            }
        }
        // Nothing can reach past the corner of its own canvas; anything beyond is clipped already.
        return minOf(furthest, CORNER)
    }

    private companion object {
        const val SIZE = 96
        const val COVERED = 8

        /** Half the canvas' diagonal: the furthest a pixel can be from the centre. */
        val CORNER = sqrt(2f)

        /** How much unused reach to tolerate before calling the declared value wasteful. */
        const val SLACK = 0.12f
    }
}
