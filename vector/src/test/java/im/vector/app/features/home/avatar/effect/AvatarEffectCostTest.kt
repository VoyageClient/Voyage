/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar.effect

import android.graphics.Bitmap
import android.graphics.Color
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Guards against an effect being so slow that a screenful of them cannot be drawn.
 *
 * Every avatar on screen is rendered by one background thread, so a frame's cost is multiplied by
 * how many are visible: an effect taking 15ms is already 0.5s a frame for a full room list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarEffectCostTest {

    @Test
    fun `no effect costs more than a frame budget to render`() {
        val texture = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val slow = mutableListOf<String>()

        for (effect in AvatarEffect.values()) {
            // Warm up, so a first-call table build is not counted as the per-frame cost.
            repeat(WARMUP) { AvatarEffectRenderer.renderStill(effect, texture, SIZE, it) }

            val started = System.nanoTime()
            repeat(FRAMES) { AvatarEffectRenderer.renderStill(effect, texture, SIZE, it) }
            val perFrameMs = (System.nanoTime() - started) / FRAMES / 1_000_000.0

            if (perFrameMs > BUDGET_MS) slow += "%s takes %.1fms a frame".format(effect, perFrameMs)
        }

        if (slow.isNotEmpty()) {
            throw AssertionError("effects too slow to animate a screenful of:\n" + slow.joinToString("\n"))
        }
    }

    @Test
    fun `the picker's whole grid of shapes costs less than one tick`() {
        // Every shape animates at once in the picker, so what decides its frame rate is their
        // combined cost, not any one of them. They are distinct effects, so there is no shared work
        // between them to save; what makes this affordable is that they render in parallel.
        val texture = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        AvatarEffect.values().forEach { AvatarEffectRenderer.renderStill(it, texture, SIZE, 0) }

        val started = System.nanoTime()
        for (frame in 0 until FRAMES) {
            AvatarEffect.values().forEach { AvatarEffectRenderer.renderStill(it, texture, SIZE, frame) }
        }
        val perTickMs = (System.nanoTime() - started) / FRAMES / 1_000_000.0

        if (perTickMs > GRID_BUDGET_MS) {
            throw AssertionError("every shape at once takes %.1fms a tick on one thread".format(perTickMs))
        }
    }

    @Test
    fun `avatars sharing a shape share the work of placing its faces`() {
        // A room list is many avatars of a single shape: same solid, same angle, same size, and only
        // the picture differs. Asserted on the cache rather than on a stopwatch, which on a shared
        // machine says more about the machine.
        val texture = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        SolidGeometryCache.clear()

        AvatarEffectRenderer.renderStill(AvatarEffect.DONUT, texture, SIZE, 3)
        val placed = SolidGeometryCache.get(AvatarEffect.DONUT, SIZE, 3)
        AvatarEffectRenderer.renderStill(AvatarEffect.DONUT, texture, SIZE, 3)
        val secondAvatar = SolidGeometryCache.get(AvatarEffect.DONUT, SIZE, 3)

        placed.shouldNotBeNull()
        // The same object, so the second avatar filled the faces rather than working them out again.
        (placed === secondAvatar).shouldBeTrue()
        // A different frame is different geometry, or the shape would not move.
        (SolidGeometryCache.get(AvatarEffect.DONUT, SIZE, 4) !== placed).shouldBeTrue()
    }

    private fun timePerFrame(render: (Int) -> Unit): Double {
        repeat(WARMUP) { render(it) }
        val started = System.nanoTime()
        for (frame in 0 until FRAMES) render(frame)
        return (System.nanoTime() - started) / FRAMES / 1_000_000.0
    }

    private companion object {
        const val SIZE = 160
        const val WARMUP = 4
        const val FRAMES = AvatarEffect.PERIOD_FRAMES

        /**
         * Deliberately loose: this is a floor under "unusable", not a performance target, and it has
         * to hold on whatever machine runs the tests.
         */
        const val BUDGET_MS = 4.0

        /**
         * The picker's grid, measured on one thread. The app spreads it over several, so this is the
         * serial total and it has room against a 50ms tick.
         */
        const val GRID_BUDGET_MS = 30.0
    }
}
