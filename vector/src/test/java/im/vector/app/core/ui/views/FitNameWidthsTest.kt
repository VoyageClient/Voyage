/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class FitNameWidthsTest {

    @Test
    fun `names that fit keep their widths`() {
        fitNameWidths(listOf(30f, 40f), 100f) shouldBeEqualTo listOf(30f, 40f)
    }

    @Test
    fun `a long name gets what the short ones leave`() {
        fitNameWidths(listOf(200f, 20f, 30f), 110f) shouldBeEqualTo listOf(60f, 20f, 30f)
    }

    @Test
    fun `long names split the budget evenly`() {
        fitNameWidths(listOf(200f, 300f, 10f), 110f) shouldBeEqualTo listOf(50f, 50f, 10f)
    }

    @Test
    fun `no budget gives every name zero`() {
        fitNameWidths(listOf(20f, 30f), 0f) shouldBeEqualTo listOf(0f, 0f)
        fitNameWidths(listOf(20f, 30f), -50f) shouldBeEqualTo listOf(0f, 0f)
    }
}
