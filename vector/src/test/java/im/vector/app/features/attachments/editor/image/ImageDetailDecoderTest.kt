/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.image

import android.graphics.RectF
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImageDetailDecoderTest {

    private val rawTopLeft = RectF(0f, 0f, 0.125f, 0.25f)

    @Test
    fun `an upright image maps straight through`() {
        ImageDetailDecoder.orientedToRaw(rawTopLeft, 0) shouldBeEqualTo rawTopLeft
    }

    @Test
    fun `a quarter turn clockwise puts the file's top-left at the top-right`() {
        // Sideways, the raw rectangle's width runs down the screen.
        val shown = RectF(0.75f, 0f, 1f, 0.125f)
        ImageDetailDecoder.rawToOriented(rawTopLeft, 90) shouldBeEqualTo shown
        ImageDetailDecoder.orientedToRaw(shown, 90) shouldBeEqualTo rawTopLeft
    }

    @Test
    fun `a half turn puts the file's top-left at the bottom-right`() {
        val shown = RectF(0.875f, 0.75f, 1f, 1f)
        ImageDetailDecoder.rawToOriented(rawTopLeft, 180) shouldBeEqualTo shown
        ImageDetailDecoder.orientedToRaw(shown, 180) shouldBeEqualTo rawTopLeft
    }

    @Test
    fun `three quarter turns put the file's top-left at the bottom-left`() {
        val shown = RectF(0f, 0.875f, 0.25f, 1f)
        ImageDetailDecoder.rawToOriented(rawTopLeft, 270) shouldBeEqualTo shown
        ImageDetailDecoder.orientedToRaw(shown, 270) shouldBeEqualTo rawTopLeft
    }

    @Test
    fun `subsampling never goes past what the zoom needs`() {
        ImageDetailDecoder.powerOfTwoAtMost(0.5f) shouldBeEqualTo 1
        ImageDetailDecoder.powerOfTwoAtMost(1f) shouldBeEqualTo 1
        ImageDetailDecoder.powerOfTwoAtMost(3.9f) shouldBeEqualTo 2
        ImageDetailDecoder.powerOfTwoAtMost(4f) shouldBeEqualTo 4
    }

    @Test
    fun `an unknown source size cannot overflow the subsampling`() {
        ImageDetailDecoder.powerOfTwoAtMost(Float.MAX_VALUE) shouldBeEqualTo (1 shl 30)
    }
}
