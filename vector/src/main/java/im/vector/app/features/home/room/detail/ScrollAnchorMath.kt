/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail

/** Where a jump target is held within the viewport. */
enum class ScrollAnchorAlignment {
    /** Vertically centered, so growth above or below it is absorbed without moving it off screen. */
    CENTER,

    /** Pinned to the top edge, for a row whose interest is what follows it (the read marker). */
    TOP
}

/**
 * Offsets for [androidx.recyclerview.widget.LinearLayoutManager.scrollToPositionWithOffset] on the
 * reverse-laid-out timeline, where an offset is the gap between the row's bottom edge and the
 * viewport's bottom padding edge.
 */
object ScrollAnchorMath {

    /**
     * Offset that puts a row of [itemHeight] where [alignment] asks for it. A row taller than the
     * viewport is always top-aligned: its start is what the reader is being sent to.
     */
    fun desiredOffset(alignment: ScrollAnchorAlignment, usableHeight: Int, itemHeight: Int): Int {
        if (usableHeight <= 0) return 0
        val slack = usableHeight - itemHeight
        return when {
            slack <= 0 -> slack
            alignment == ScrollAnchorAlignment.TOP -> slack
            else -> slack / 2
        }
    }

    fun currentOffset(endAfterPadding: Int, decoratedBottom: Int): Int = endAfterPadding - decoratedBottom
}
