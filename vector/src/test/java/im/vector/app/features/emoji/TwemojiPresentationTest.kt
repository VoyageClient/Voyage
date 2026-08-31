/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.emoji

import android.text.Spanned
import im.vector.app.features.settings.VectorPreferences
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TwemojiPresentationTest {

    private val vectorPreferences = mockk<VectorPreferences> {
        every { useTwemoji() } returns true
    }

    private val provider = TwemojiProvider(RuntimeEnvironment.getApplication(), vectorPreferences)

    private fun spanCount(text: String): Int {
        val result = provider.spanify(text)
        return (result as? Spanned)?.getSpans(0, result.length, TwemojiSpan::class.java)?.size ?: 0
    }

    @Test
    fun `text presentation defaults are left alone`() {
        spanCount("Voyage™ © 2026 ®") shouldBeEqualTo 0
        spanCount("a ❤ b ☝ c ✈") shouldBeEqualTo 0
    }

    @Test
    fun `an explicit variation selector opts back in`() {
        spanCount("Voyage™️") shouldBeEqualTo 1
        spanCount("❤️") shouldBeEqualTo 1
    }

    @Test
    fun `skin tones and joiners keep their sequence`() {
        spanCount("☝🏻") shouldBeEqualTo 1
        spanCount("❤‍🔥") shouldBeEqualTo 1
    }

    @Test
    fun `emoji presentation defaults still render`() {
        spanCount("😀 🎉") shouldBeEqualTo 2
    }
}
