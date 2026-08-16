/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.view.View
import android.view.View.MeasureSpec
import im.vector.app.features.home.room.detail.timeline.item.GalleryLayoutHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GalleryGridLayoutTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun aGrid(tileCount: Int, aspects: List<Float>, maxWidth: Int = 800): GalleryGridLayout {
        val grid = GalleryGridLayout(context)
        grid.maxContentWidth = maxWidth
        repeat(tileCount) { grid.addView(View(context)) }
        val layout = GalleryLayoutHelper.layout(aspects)
        grid.setLayoutSpec(layout.tiles, layout.totalHeight)
        return grid
    }

    private fun GalleryGridLayout.measureAndLayout(availableWidth: Int) {
        measure(
                MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        layout(0, 0, measuredWidth, measuredHeight)
    }

    @Test
    fun `grid measures to the layout's total height`() {
        val aspects = listOf(1f, 1f)
        val expected = GalleryLayoutHelper.layout(aspects)
        val grid = aGrid(2, aspects)
        grid.measureAndLayout(1000)
        assertEquals(800, grid.measuredWidth)
        assertEquals((expected.totalHeight * 800).roundToInt(), grid.measuredHeight)
    }

    @Test
    fun `available width caps the grid below maxContentWidth`() {
        val grid = aGrid(2, listOf(1f, 1f), maxWidth = 800)
        grid.measureAndLayout(500)
        assertEquals(500, grid.measuredWidth)
    }

    @Test
    fun `two tiles split the width with a gutter between them`() {
        val grid = aGrid(2, listOf(1f, 1f))
        grid.measureAndLayout(800)
        val left = grid.getChildAt(0)
        val right = grid.getChildAt(1)
        assertEquals(0, left.left)
        assertEquals(800, right.right)
        // Inner edges are inset by the 1dp gutter on each side.
        assertTrue(left.right < 400)
        assertTrue(right.left > 400 - 1)
        assertEquals(left.top, right.top)
        assertEquals(left.bottom, right.bottom)
        assertTrue(left.width > 0 && left.height > 0)
    }

    @Test
    fun `three tiles place tall left and two stacked right`() {
        val grid = aGrid(3, listOf(1f, 1f, 1f))
        grid.measureAndLayout(800)
        val tall = grid.getChildAt(0)
        val topRight = grid.getChildAt(1)
        val bottomRight = grid.getChildAt(2)
        assertEquals(0, tall.left)
        assertEquals(0, tall.top)
        assertEquals(grid.measuredHeight, tall.bottom)
        assertEquals(800, topRight.right)
        assertEquals(800, bottomRight.right)
        assertEquals(0, topRight.top)
        assertEquals(grid.measuredHeight, bottomRight.bottom)
        assertTrue(topRight.bottom <= bottomRight.top)
    }

    @Test
    fun `extra children beyond the tile list are hidden`() {
        val grid = aGrid(4, listOf(1f, 1f))
        grid.measureAndLayout(800)
        assertEquals(View.VISIBLE, grid.getChildAt(0).visibility)
        assertEquals(View.VISIBLE, grid.getChildAt(1).visibility)
        assertEquals(View.GONE, grid.getChildAt(2).visibility)
        assertEquals(View.GONE, grid.getChildAt(3).visibility)
    }

    @Test
    fun `unspecified width falls back to maxContentWidth`() {
        val grid = aGrid(2, listOf(1f, 1f), maxWidth = 640)
        grid.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        assertEquals(640, grid.measuredWidth)
    }
}
