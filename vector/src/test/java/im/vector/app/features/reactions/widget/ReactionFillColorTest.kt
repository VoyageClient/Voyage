/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.reactions.widget

import android.graphics.Color
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeLessThan
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReactionFillColorTest {

    private val elementGreen = Color.parseColor("#FF11BC8A")
    private val scLimeGreen = Color.parseColor("#FF7CB342")

    @Test
    fun `fill keeps the accent hue`() {
        val fill = reactionFillColor(scLimeGreen, DARK_PILL_BACKGROUND)

        Color.red(fill) shouldBeEqualTo Color.red(scLimeGreen)
        Color.green(fill) shouldBeEqualTo Color.green(scLimeGreen)
        Color.blue(fill) shouldBeEqualTo Color.blue(scLimeGreen)
    }

    @Test
    fun `fill is translucent on a dark pill background`() {
        Color.alpha(reactionFillColor(scLimeGreen, DARK_PILL_BACKGROUND)) shouldBeEqualTo 0x40
    }

    @Test
    fun `fill is translucent on a light pill background`() {
        Color.alpha(reactionFillColor(elementGreen, LIGHT_PILL_BACKGROUND)) shouldBeEqualTo 0x20
    }

    @Test
    fun `an opaque themed pill background never yields a solid fill`() {
        // Every theme's pill background is opaque; the fill must never inherit that opacity.
        listOf(SC_BLACK_PILL_BACKGROUND, DARK_PILL_BACKGROUND, LIGHT_PILL_BACKGROUND, SC_LIGHT_PILL_BACKGROUND)
                .forEach { background ->
                    Color.alpha(reactionFillColor(scLimeGreen, background)) shouldBeLessThan 0x80
                }
    }

    companion object {
        private val SC_BLACK_PILL_BACKGROUND = Color.parseColor("#FF303030")
        private val DARK_PILL_BACKGROUND = Color.parseColor("#FF22252B")
        private val LIGHT_PILL_BACKGROUND = Color.parseColor("#FFF3F8FD")
        private val SC_LIGHT_PILL_BACKGROUND = Color.parseColor("#FFEEEEEE")
    }
}
