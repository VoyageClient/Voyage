/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import kotlin.math.abs

/**
 * Computes the MSC4274 gallery grid. All coordinates are relative to a grid width of 1;
 * the grid's height is [GalleryLayoutResult.totalHeight] times its width.
 */
object GalleryLayoutHelper {

    const val FLAG_LEFT = 1
    const val FLAG_RIGHT = 2
    const val FLAG_TOP = 4
    const val FLAG_BOTTOM = 8

    /** [flags] marks the outer grid edges the tile touches, for corner rounding and gutters. */
    data class GalleryTile(val x: Float, val y: Float, val w: Float, val h: Float, val flags: Int)

    data class GalleryLayoutResult(val tiles: List<GalleryTile>, val totalHeight: Float)

    private const val EDGE_ALL = FLAG_LEFT or FLAG_RIGHT or FLAG_TOP or FLAG_BOTTOM

    fun layout(aspects: List<Float>): GalleryLayoutResult {
        val safe = aspects.map { if (it.isFinite() && it > 0f) it else 1f }
        return when (safe.size) {
            0 -> GalleryLayoutResult(emptyList(), 0f)
            1 -> single(safe[0])
            2 -> twoSideBySide()
            3 -> tallLeftTwoRight()
            4 -> twoByTwo(safe)
            else -> packRows(safe)
        }
    }

    private fun single(aspect: Float): GalleryLayoutResult {
        val h = (1f / aspect).coerceIn(0.45f, 1.2f)
        return GalleryLayoutResult(listOf(GalleryTile(0f, 0f, 1f, h, EDGE_ALL)), h)
    }

    private fun twoSideBySide(): GalleryLayoutResult {
        // The pair fills a 4:3 block, each tile a tall half cropped to fit.
        val h = 0.75f
        return GalleryLayoutResult(
                listOf(
                        GalleryTile(0f, 0f, 0.5f, h, FLAG_LEFT or FLAG_TOP or FLAG_BOTTOM),
                        GalleryTile(0.5f, 0f, 0.5f, h, FLAG_RIGHT or FLAG_TOP or FLAG_BOTTOM),
                ),
                h,
        )
    }

    private fun tallLeftTwoRight(): GalleryLayoutResult {
        // 4:3 overall: full-height left half, two quarters stacked on the right.
        val h = 0.75f
        return GalleryLayoutResult(
                listOf(
                        GalleryTile(0f, 0f, 0.5f, h, FLAG_LEFT or FLAG_TOP or FLAG_BOTTOM),
                        GalleryTile(0.5f, 0f, 0.5f, h / 2f, FLAG_RIGHT or FLAG_TOP),
                        GalleryTile(0.5f, h / 2f, 0.5f, h / 2f, FLAG_RIGHT or FLAG_BOTTOM),
                ),
                h,
        )
    }

    private fun twoByTwo(aspects: List<Float>): GalleryLayoutResult {
        val avg = aspects.sum() / aspects.size
        val h = (1f / avg).coerceIn(0.7f, 0.8f)
        val half = h / 2f
        return GalleryLayoutResult(
                listOf(
                        GalleryTile(0f, 0f, 0.5f, half, FLAG_LEFT or FLAG_TOP),
                        GalleryTile(0.5f, 0f, 0.5f, half, FLAG_RIGHT or FLAG_TOP),
                        GalleryTile(0f, half, 0.5f, half, FLAG_LEFT or FLAG_BOTTOM),
                        GalleryTile(0.5f, half, 0.5f, half, FLAG_RIGHT or FLAG_BOTTOM),
                ),
                h,
        )
    }

    /**
     * Layout for larger counts: try every partition of the items into 2..4 lines (max 3 per line),
     * and keep the one whose stacked height comes closest to a 4:3 block.
     */
    private fun packRows(rawAspects: List<Float>): GalleryLayoutResult {
        val ratios = rawAspects.map { it.coerceIn(0.66667f, 1.7f) }
        val count = ratios.size
        val maxLineItems = 3
        val maxLines = 4
        if (count > maxLineItems * maxLines) {
            return rowsOfThree(ratios)
        }
        val targetHeight = 0.75f

        var best: List<Int>? = null
        var bestDiff = Float.MAX_VALUE
        fun consider(lines: List<Int>) {
            var height = 0f
            var start = 0
            for (n in lines) {
                height += lineHeight(ratios, start, start + n)
                start += n
            }
            var diff = abs(height - targetHeight)
            for (i in 1 until lines.size) {
                // Prefer a pyramid: a wide row above a narrow one reads as a mistake.
                if (lines[i] < lines[i - 1]) diff *= 1.2f
            }
            if (diff < bestDiff) {
                bestDiff = diff
                best = lines
            }
        }

        fun enumerate(remaining: Int, acc: MutableList<Int>) {
            if (remaining == 0) {
                if (acc.size in 2..maxLines) consider(acc.toList())
                return
            }
            if (acc.size >= maxLines) return
            for (n in 1..minOf(maxLineItems, remaining)) {
                acc.add(n)
                enumerate(remaining - n, acc)
                acc.removeAt(acc.size - 1)
            }
        }
        enumerate(count, mutableListOf())

        val lines = best ?: return rowsOfThree(ratios)
        return emitLines(ratios, lines)
    }

    /** Fallback for absurd counts the partition search can't cover. */
    private fun rowsOfThree(ratios: List<Float>): GalleryLayoutResult {
        val lines = mutableListOf<Int>()
        var remaining = ratios.size
        while (remaining > 0) {
            val n = minOf(3, remaining)
            lines.add(n)
            remaining -= n
        }
        return emitLines(ratios, lines)
    }

    private fun lineHeight(ratios: List<Float>, start: Int, end: Int): Float {
        var sum = 0f
        for (i in start until end) sum += ratios[i]
        return 1f / sum
    }

    private fun emitLines(ratios: List<Float>, lines: List<Int>): GalleryLayoutResult {
        val tiles = mutableListOf<GalleryTile>()
        var y = 0f
        var index = 0
        lines.forEachIndexed { lineIndex, n ->
            val lineHeight = lineHeight(ratios, index, index + n)
            var x = 0f
            for (i in 0 until n) {
                // The last tile absorbs float rounding so every row ends flush.
                val w = if (i == n - 1) 1f - x else ratios[index] * lineHeight
                var flags = 0
                if (lineIndex == 0) flags = flags or FLAG_TOP
                if (lineIndex == lines.size - 1) flags = flags or FLAG_BOTTOM
                if (i == 0) flags = flags or FLAG_LEFT
                if (i == n - 1) flags = flags or FLAG_RIGHT
                tiles.add(GalleryTile(x, y, w, lineHeight, flags))
                x += w
                index++
            }
            y += lineHeight
        }
        return GalleryLayoutResult(tiles, y)
    }
}
