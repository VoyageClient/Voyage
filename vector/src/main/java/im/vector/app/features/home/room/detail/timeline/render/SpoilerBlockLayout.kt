/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.render

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.animation.doOnEnd
import im.vector.app.features.themes.ThemeUtils

/**
 * Hides a block that a spoiler wrapped — a code block or a table — behind the spoiler tint until it is
 * tapped, then fades the cover away.
 *
 * Inline spoilers blur their glyphs with a BlurMaskFilter, which only applies to text paint, so a block
 * of arbitrary views is covered rather than blurred. The block keeps its real size underneath, so
 * revealing it does not move anything around it.
 */
class SpoilerBlockLayout(context: Context) : FrameLayout(context) {

    private val coverPaint = Paint().apply {
        color = ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_spoiler_background_color)
    }

    private var coverAlpha = 1f
    private var animator: ValueAnimator? = null

    val isRevealed: Boolean get() = coverAlpha == 0f

    fun reveal() {
        if (isRevealed) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(coverAlpha, 0f).apply {
            duration = REVEAL_DURATION_MS
            addUpdateListener {
                coverAlpha = it.animatedValue as Float
                invalidate()
            }
            doOnEnd { coverAlpha = 0f }
            start()
        }
    }

    /** Covered content must not be reachable: a tap reveals it instead of scrolling or selecting it. */
    override fun onInterceptTouchEvent(ev: MotionEvent?) = !isRevealed

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (coverAlpha <= 0f) return
        coverPaint.alpha = (coverAlpha * 0xFF).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), coverPaint)
    }

    companion object {
        private const val REVEAL_DURATION_MS = 200L

        /** Wraps [block] so it stays hidden until tapped, when [interactive]; a preview stays covered. */
        fun cover(block: View, interactive: Boolean, onLongClick: (View) -> Boolean): View {
            return SpoilerBlockLayout(block.context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                addView(block)
                if (interactive) {
                    setOnClickListener { reveal() }
                    setOnLongClickListener { onLongClick(it) }
                } else {
                    isClickable = false
                }
            }
        }
    }
}
