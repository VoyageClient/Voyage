/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * An image cell whose size comes from its layout, never from its picture.
 *
 * [android.widget.ImageView.setImageDrawable] asks for a layout pass whenever the new drawable's
 * intrinsic size differs from the old one's. Inside a RecyclerView that request forces a full
 * onLayoutChildren, which rebinds and re-lays-out every visible cell — each of which sets a drawable
 * of its own. In a picker grid holding ~150 cells, every image that lands relayouts the whole grid,
 * and the grid never catches up with a scroll.
 *
 * Only for cells with fixed dimensions (a fixed height and a span-derived width), where a new picture
 * genuinely cannot change the layout.
 */
class FixedSizeImageView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    @SuppressLint("MissingSuperCall")
    override fun requestLayout() {
        // Marked for layout without asking the tree for a traversal: the next pass will lay it out, and
        // drawing in the meantime uses the bounds it already has.
        forceLayout()
    }
}
