/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.LinearLayout
import im.vector.app.R

/**
 * Room screen root that keeps the emoji panel and the keyboard sharing one piece of space.
 *
 * The strip's height is resolved during measure, from the height the window still has: whatever the
 * system already took for the keyboard is subtracted from what the panel asked for. Doing it here rather
 * than from a layout listener means a window resize and the strip shrinking to match land in the same
 * traversal, so the composer never flickers a frame at the wrong height.
 */
class EmojiPanelHostLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private var stripView: ViewGroup? = null
    private var desiredStripHeight = 0
    private var unshrunkHeight = 0

    /** The strip itself; the emoji panel is parented here. */
    val strip: ViewGroup get() = checkNotNull(stripView) { "emojiPanelContainer missing" }

    override fun onFinishInflate() {
        super.onFinishInflate()
        stripView = findViewById(R.id.emojiPanelContainer)
    }

    /** Height the panel wants, as if the keyboard were not taking any of the window. */
    fun setDesiredStripHeight(px: Int) {
        if (desiredStripHeight == px) return
        desiredStripHeight = px
        requestLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Rotation or a multi-window resize: the old full height means nothing now.
        if (w != oldw) unshrunkHeight = 0
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(heightMeasureSpec)
        if (available > unshrunkHeight) unshrunkHeight = available
        val takenByKeyboard = (unshrunkHeight - available).coerceAtLeast(0)
        val height = (desiredStripHeight - takenByKeyboard).coerceAtLeast(0)
        // In-place so this measure pass uses it; assigning layoutParams would schedule another one.
        stripView?.layoutParams?.let { if (it.height != height) it.height = height }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
