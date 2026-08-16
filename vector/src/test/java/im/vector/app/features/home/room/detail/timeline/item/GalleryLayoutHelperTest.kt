/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GalleryLayoutHelperTest {

    private fun Float.near(other: Float) = abs(this - other) < 1e-4f

    @Test
    fun `two items sit side by side`() {
        val result = GalleryLayoutHelper.layout(listOf(1f, 1f))
        assertEquals(2, result.tiles.size)
        val (left, right) = result.tiles
        assertTrue(left.x.near(0f) && left.w.near(0.5f))
        assertTrue(right.x.near(0.5f) && right.w.near(0.5f))
        assertTrue(left.h.near(right.h))
        assertTrue(left.flags and GalleryLayoutHelper.FLAG_LEFT != 0)
        assertTrue(right.flags and GalleryLayoutHelper.FLAG_RIGHT != 0)
    }

    @Test
    fun `three items make tall left plus two stacked right`() {
        val result = GalleryLayoutHelper.layout(listOf(1f, 1f, 1f))
        assertEquals(3, result.tiles.size)
        val (tall, topRight, bottomRight) = result.tiles
        assertTrue(tall.x.near(0f) && tall.w.near(0.5f) && tall.h.near(result.totalHeight))
        assertTrue(topRight.x.near(0.5f) && topRight.y.near(0f) && topRight.h.near(result.totalHeight / 2f))
        assertTrue(bottomRight.x.near(0.5f) && bottomRight.y.near(result.totalHeight / 2f))
        assertTrue(tall.flags and GalleryLayoutHelper.FLAG_TOP != 0 && tall.flags and GalleryLayoutHelper.FLAG_BOTTOM != 0)
        assertTrue(topRight.flags and GalleryLayoutHelper.FLAG_BOTTOM == 0)
    }

    @Test
    fun `four items split evenly into corners`() {
        val result = GalleryLayoutHelper.layout(listOf(1f, 1f, 1f, 1f))
        assertEquals(4, result.tiles.size)
        val half = result.totalHeight / 2f
        result.tiles.forEach {
            assertTrue(it.w.near(0.5f))
            assertTrue(it.h.near(half))
        }
        assertTrue(result.tiles[0].x.near(0f) && result.tiles[0].y.near(0f))
        assertTrue(result.tiles[1].x.near(0.5f) && result.tiles[1].y.near(0f))
        assertTrue(result.tiles[2].x.near(0f) && result.tiles[2].y.near(half))
        assertTrue(result.tiles[3].x.near(0.5f) && result.tiles[3].y.near(half))
    }

    @Test
    fun `larger counts fill every row flush and flag edges correctly`() {
        for (count in 5..12) {
            val aspects = List(count) { 0.7f + (it % 5) * 0.3f }
            val result = GalleryLayoutHelper.layout(aspects)
            assertEquals(count, result.tiles.size)
            // Group into rows by y
            val rows = result.tiles.groupBy { it.y }.toSortedMap()
            rows.values.forEach { row ->
                val sorted = row.sortedBy { it.x }
                assertTrue(sorted.first().x.near(0f))
                assertTrue((sorted.last().x + sorted.last().w).near(1f))
                assertTrue(sorted.first().flags and GalleryLayoutHelper.FLAG_LEFT != 0)
                assertTrue(sorted.last().flags and GalleryLayoutHelper.FLAG_RIGHT != 0)
            }
            val firstRow = rows.values.first()
            val lastRow = rows.values.last()
            assertTrue(firstRow.all { it.flags and GalleryLayoutHelper.FLAG_TOP != 0 })
            assertTrue(lastRow.all { it.flags and GalleryLayoutHelper.FLAG_BOTTOM != 0 })
            assertTrue(result.totalHeight > 0f)
        }
    }

    @Test
    fun `degenerate aspects fall back to square`() {
        val result = GalleryLayoutHelper.layout(listOf(Float.NaN, 0f, -3f))
        assertEquals(3, result.tiles.size)
    }

    @Test
    fun `no tiles overlap for any count`() {
        for (count in 1..14) {
            val aspects = List(count) { 0.5f + (it % 7) * 0.4f }
            val tiles = GalleryLayoutHelper.layout(aspects).tiles
            for (i in tiles.indices) {
                for (j in i + 1 until tiles.size) {
                    val a = tiles[i]
                    val b = tiles[j]
                    val overlapX = minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x)
                    val overlapY = minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y)
                    assertTrue(
                            "tiles $i and $j overlap for count $count",
                            overlapX <= 1e-4f || overlapY <= 1e-4f
                    )
                }
            }
        }
    }

    @Test
    fun `all tiles stay within the grid bounds`() {
        for (count in 1..14) {
            val aspects = List(count) { 0.6f + (it % 4) * 0.5f }
            val result = GalleryLayoutHelper.layout(aspects)
            result.tiles.forEach {
                assertTrue(it.x >= -1e-4f && it.y >= -1e-4f)
                assertTrue(it.x + it.w <= 1f + 1e-4f)
                assertTrue(it.y + it.h <= result.totalHeight + 1e-4f)
                assertTrue(it.w > 0f && it.h > 0f)
            }
        }
    }

    @Test
    fun `extreme aspect ratios produce no degenerate tiles`() {
        val result = GalleryLayoutHelper.layout(listOf(10f, 0.05f, 10f, 0.1f, 8f, 0.2f))
        assertEquals(6, result.tiles.size)
        result.tiles.forEach {
            // Solver clamps ratios into [0.667, 1.7], so no sliver tiles.
            assertTrue(it.w >= 0.1f)
            assertTrue(it.h >= 0.1f)
        }
    }

    @Test
    fun `single item height is clamped`() {
        val tall = GalleryLayoutHelper.layout(listOf(0.1f))
        assertTrue(tall.totalHeight.near(1.2f))
        val wide = GalleryLayoutHelper.layout(listOf(10f))
        assertTrue(wide.totalHeight.near(0.45f))
    }

    @Test
    fun `oversized counts fall back to rows of three`() {
        val count = 40
        val result = GalleryLayoutHelper.layout(List(count) { 1f })
        assertEquals(count, result.tiles.size)
        val rows = result.tiles.groupBy { it.y }
        assertEquals(14, rows.size)
        rows.values.forEach { row -> assertTrue(row.size <= 3) }
    }
}
