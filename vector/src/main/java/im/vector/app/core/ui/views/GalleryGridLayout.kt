/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.ViewGroup
import im.vector.app.features.home.room.detail.timeline.item.GalleryLayoutHelper
import im.vector.app.features.home.room.detail.timeline.item.GalleryLayoutHelper.FLAG_BOTTOM
import im.vector.app.features.home.room.detail.timeline.item.GalleryLayoutHelper.FLAG_LEFT
import im.vector.app.features.home.room.detail.timeline.item.GalleryLayoutHelper.FLAG_RIGHT
import im.vector.app.features.home.room.detail.timeline.item.GalleryLayoutHelper.FLAG_TOP
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Positions its children according to a precomputed [GalleryLayoutHelper] result.
 * Child i is laid out at tile i; extra children are hidden.
 */
class GalleryGridLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    private var tiles: List<GalleryLayoutHelper.GalleryTile> = emptyList()
    private var totalHeight = 0f
    private val childRects = mutableListOf<Rect>()
    private val gutterPx = (context.resources.displayMetrics.density + 0.5f).toInt().coerceAtLeast(1)

    /** Bounded so an UNSPECIFIED measure cannot ask for a grid the size of an int. */
    var maxContentWidth: Int = DEFAULT_MAX_WIDTH_PX

    fun setLayoutSpec(tiles: List<GalleryLayoutHelper.GalleryTile>, totalHeight: Float) {
        this.tiles = tiles
        this.totalHeight = totalHeight
        // Visibility is settled here rather than in onMeasure, where it would request another layout.
        for (i in 0 until childCount) {
            getChildAt(i).visibility = if (i < tiles.size) VISIBLE else GONE
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            // An exact width is not ours to shrink: the parent lays us out at it either way, and the
            // tiles have to fill what the rounded outline will cover.
            MeasureSpec.EXACTLY -> available
            MeasureSpec.AT_MOST -> min(available, maxContentWidth)
            else -> maxContentWidth
        }
        val height = (totalHeight * width).roundToInt()
        childRects.clear()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val tile = tiles.getOrNull(i)
            if (tile == null) {
                childRects.add(Rect())
                continue
            }
            var l = (tile.x * width).roundToInt()
            var t = (tile.y * width).roundToInt()
            var r = ((tile.x + tile.w) * width).roundToInt()
            var b = ((tile.y + tile.h) * width).roundToInt()
            if (tile.flags and FLAG_LEFT == 0) l += gutterPx
            if (tile.flags and FLAG_TOP == 0) t += gutterPx
            if (tile.flags and FLAG_RIGHT == 0) r -= gutterPx
            if (tile.flags and FLAG_BOTTOM == 0) b -= gutterPx
            childRects.add(Rect(l, t, r, b))
            child.measure(
                    MeasureSpec.makeMeasureSpec((r - l).coerceAtLeast(0), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec((b - t).coerceAtLeast(0), MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val rect = childRects.getOrNull(i) ?: continue
            child.layout(rect.left, rect.top, rect.right, rect.bottom)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

    override fun shouldDelayChildPressedState(): Boolean = false

    companion object {
        private const val DEFAULT_MAX_WIDTH_PX = 1080
    }
}
