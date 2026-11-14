/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.tools

import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ClampForMaxLinesTest {

    @Test
    fun `short text is returned unchanged`() {
        val text = "Reason: let me in"
        text.clampForMaxLines(3) shouldBe text
    }

    @Test
    fun `unlimited lines never clamps`() {
        val text = "a".repeat(100_000)
        text.clampForMaxLines(Int.MAX_VALUE) shouldBe text
    }

    @Test
    fun `long text is cut and ellipsized`() {
        val clamped = "a".repeat(10_000).clampForMaxLines(3).toString()
        clamped.length shouldBeEqualTo 601
        clamped.last() shouldBeEqualTo '…'
    }

    @Test
    fun `cut never splits a surrogate pair`() {
        val text = "a".repeat(599) + "😀".repeat(10)
        val clamped = text.clampForMaxLines(3).toString()
        clamped shouldBeEqualTo "a".repeat(599) + "…"
    }

    @Test
    fun `spans are kept`() {
        val text = SpannableString("b".repeat(1_000)).apply { setSpan(StyleSpan(0), 0, 10, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val clamped = text.clampForMaxLines(1) as Spanned
        clamped.getSpans(0, clamped.length, StyleSpan::class.java).size shouldBeEqualTo 1
    }
}
