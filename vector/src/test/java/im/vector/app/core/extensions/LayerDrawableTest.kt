/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LayerDrawableTest {

    private val layers = LayerDrawable(arrayOf(ColorDrawable(Color.BLACK), ColorDrawable(Color.GRAY))).apply {
        setBounds(0, 0, 1080, 2400)
    }

    @Test
    fun `a strip resized at the same bounds takes its new height at once`() {
        layers.setLayerInsetNow(1, 0, 0, 0, 2400 - 74, 1080, 2400)

        layers.setLayerInsetNow(1, 0, 0, 0, 2400 - 121, 1080, 2400)

        layers.getDrawable(1).bounds shouldBeEqualTo Rect(0, 0, 1080, 121)
    }

    @Test
    fun `the framework inset alone leaves the strip at its old height`() {
        layers.setLayerInsetNow(1, 0, 0, 0, 2400 - 74, 1080, 2400)

        layers.setLayerInset(1, 0, 0, 0, 2400 - 121)

        layers.getDrawable(1).bounds shouldBeEqualTo Rect(0, 0, 1080, 74)
    }
}
