/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import im.vector.app.R
import kotlin.math.roundToInt

/**
 * Holds a timeline thumbnail, which is given a fixed size, and shrinks it into whatever width this row
 * has, keeping its shape. ConstraintLayout measures a fixed-size child at that size however narrow the
 * row, so the bubble would crop it, rounded corners and all: the size lands after the row is measured
 * (Glide resolves real bounds later, a recycled row starts from the previous media's size), and
 * selection narrows every row while a message is shown. Done on every measure, so it also grows back.
 * The size the renderer set is the cap, never a target.
 */
class TimelineMediaContainer @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val thumbnail: View? by lazy { findViewById(R.id.messageThumbnailView) }

    // The renderer's size, and what this last shrank it to; a size other than the latter is a new cap.
    private var capWidth = 0
    private var capHeight = 0
    private var fittedWidth = 0
    private var fittedHeight = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fitThumbnail(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun fitThumbnail(widthMeasureSpec: Int) {
        val params = thumbnail?.layoutParams ?: return
        if (params.width != fittedWidth || params.height != fittedHeight) {
            capWidth = params.width
            capHeight = params.height
        }
        // Wrapping or matching sizes come from the picture or the row already.
        if (capWidth <= 0 || capHeight <= 0 || MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) return
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        if (available <= 0) return
        val width = minOf(capWidth, available)
        val height = if (width == capWidth) capHeight else (capHeight * width.toFloat() / capWidth).roundToInt().coerceAtLeast(1)
        fittedWidth = width
        fittedHeight = height
        if (params.width == width && params.height == height) return
        // Written straight into the params for this measure pass to read. ConstraintLayout only re-reads
        // its children's params when its hierarchy is dirty, which a requestLayout() from here can't mark.
        params.width = width
        params.height = height
        mDirtyHierarchy = true
    }
}
