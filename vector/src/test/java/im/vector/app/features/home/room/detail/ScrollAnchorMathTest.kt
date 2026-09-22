/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class ScrollAnchorMathTest {

    @Test
    fun `centered row sits in the middle of the viewport`() {
        val offset = ScrollAnchorMath.desiredOffset(ScrollAnchorAlignment.CENTER, usableHeight = 1000, itemHeight = 200)

        offset shouldBeEqualTo 400
        // Bottom edge 400px above the bottom, top edge 400px below the top.
        bottomOf(offset, usableHeight = 1000) shouldBeEqualTo 600
    }

    @Test
    fun `a row growing in place keeps its center`() {
        val small = ScrollAnchorMath.desiredOffset(ScrollAnchorAlignment.CENTER, usableHeight = 1000, itemHeight = 100)
        val grown = ScrollAnchorMath.desiredOffset(ScrollAnchorAlignment.CENTER, usableHeight = 1000, itemHeight = 500)

        centerOf(small, usableHeight = 1000, itemHeight = 100) shouldBeEqualTo 500
        centerOf(grown, usableHeight = 1000, itemHeight = 500) shouldBeEqualTo 500
    }

    @Test
    fun `top aligned row sits against the top edge`() {
        val offset = ScrollAnchorMath.desiredOffset(ScrollAnchorAlignment.TOP, usableHeight = 1000, itemHeight = 200)

        offset shouldBeEqualTo 800
        topOf(offset, usableHeight = 1000, itemHeight = 200) shouldBeEqualTo 0
    }

    @Test
    fun `a row taller than the viewport is top aligned whatever the alignment`() {
        val centered = ScrollAnchorMath.desiredOffset(ScrollAnchorAlignment.CENTER, usableHeight = 1000, itemHeight = 1500)
        val top = ScrollAnchorMath.desiredOffset(ScrollAnchorAlignment.TOP, usableHeight = 1000, itemHeight = 1500)

        centered shouldBeEqualTo -500
        top shouldBeEqualTo -500
        topOf(centered, usableHeight = 1000, itemHeight = 1500) shouldBeEqualTo 0
    }

    @Test
    fun `an unmeasured viewport asks for no scrolling`() {
        ScrollAnchorMath.desiredOffset(ScrollAnchorAlignment.CENTER, usableHeight = 0, itemHeight = 200) shouldBeEqualTo 0
    }

    @Test
    fun `current offset is the gap under the row`() {
        ScrollAnchorMath.currentOffset(endAfterPadding = 1000, decoratedBottom = 600) shouldBeEqualTo 400
        // Row hanging below the viewport: negative, which is how far it has to come back up.
        ScrollAnchorMath.currentOffset(endAfterPadding = 1000, decoratedBottom = 1200) shouldBeEqualTo -200
    }

    private fun bottomOf(offset: Int, usableHeight: Int) = usableHeight - offset

    private fun topOf(offset: Int, usableHeight: Int, itemHeight: Int) = bottomOf(offset, usableHeight) - itemHeight

    private fun centerOf(offset: Int, usableHeight: Int, itemHeight: Int) =
            bottomOf(offset, usableHeight) - itemHeight / 2
}
