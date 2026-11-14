/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.selection

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import im.vector.app.features.themes.ThemeUtils

/** Commit row insets once per mode change; redraw selection controls independently. */
class TimelineSelectionDecoration(
        context: Context,
        private val recyclerView: RecyclerView,
        private val selection: TimelineSelection,
        private val selectableEventIdOf: (child: View) -> String?,
        private val isMessageRow: (child: View) -> Boolean,
        private val rowTintOf: (child: View) -> Int?,
) : RecyclerView.ItemDecoration() {

    private val density = context.resources.displayMetrics.density
    private val gutter = (40 * density).toInt()
    private val radius = 10 * density
    private val accent = ThemeUtils.getColor(context, com.google.android.material.R.attr.colorPrimary)
    private val ringColor = ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_tertiary)

    private val tintPaint = Paint().apply { color = ColorUtils.setAlphaComponent(accent, 0x2E) }
    private val rowTintPaint = Paint()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = ringColor
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val checkPath = Path()

    private var shown = if (selection.isActive) 1f else 0f
    private var animator: ValueAnimator? = null
    private var gutterVisible = selection.isActive

    fun animateGutter(active: Boolean) {
        animator?.cancel()
        if (gutterVisible != active) {
            gutterVisible = active
            relayoutGutter()
        }
        animator = ValueAnimator.ofFloat(shown, if (active) 1f else 0f).apply {
            duration = SLIDE_MS
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                shown = it.animatedValue as Float
                recyclerView.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) animator = null
                }
            })
            start()
        }
    }

    private fun relayoutGutter() {
        if (recyclerView.isComputingLayout) {
            recyclerView.post { recyclerView.invalidateItemDecorations() }
        } else {
            recyclerView.invalidateItemDecorations()
        }
    }

    fun dispose() {
        animator?.cancel()
        animator = null
    }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        if (!gutterVisible || !isMessageRow(view)) return
        if (isRtl()) outRect.right = gutter else outRect.left = gutter
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (shown <= 0f) return
        val rtl = isRtl()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val top = child.top + child.translationY
            rowTintOf(child)?.let { color ->
                rowTintPaint.color = color
                if (rtl) {
                    c.drawRect(child.right + child.translationX, top, parent.width.toFloat(), top + child.height, rowTintPaint)
                } else {
                    c.drawRect(0f, top, child.left + child.translationX, top + child.height, rowTintPaint)
                }
            }
            val eventId = selectableEventIdOf(child) ?: continue
            if (eventId in selection) {
                c.drawRect(0f, top, parent.width.toFloat(), top + child.height, tintPaint)
            }
        }
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (shown <= 0f) return
        val rtl = isRtl()
        // Slides in from just off the start edge, keeping to the middle of the gutter as it grows.
        val startCx = shown * gutter - gutter / 2f
        val cx = if (rtl) parent.width - startCx else startCx
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val eventId = selectableEventIdOf(child) ?: continue
            val cy = child.top + child.translationY + child.height / 2f
            if (eventId in selection) {
                c.drawCircle(cx, cy, radius, fillPaint)
                checkPath.reset()
                checkPath.moveTo(cx - radius * 0.45f, cy + radius * 0.02f)
                checkPath.lineTo(cx - radius * 0.1f, cy + radius * 0.38f)
                checkPath.lineTo(cx + radius * 0.48f, cy - radius * 0.32f)
                c.drawPath(checkPath, checkPaint)
            } else {
                c.drawCircle(cx, cy, radius - ringPaint.strokeWidth / 2, ringPaint)
            }
        }
    }

    private fun isRtl() = ViewCompat.getLayoutDirection(recyclerView) == ViewCompat.LAYOUT_DIRECTION_RTL

    companion object {
        private const val SLIDE_MS = 220L
    }
}
