/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.EpoxyControllerAdapter
import com.google.android.material.color.MaterialColors
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.features.home.room.detail.timeline.item.DaySeparatorItem

/** Telegram-style date pill: stands in for the top day divider while scrolling; the next divider pushes it up. */
class FloatingDateHeader(
        private val view: TextView,
        private val layoutManager: LinearLayoutManager,
        private val adapter: EpoxyControllerAdapter,
        private val obstruction: View,
) : RecyclerView.OnScrollListener() {

    private val density = view.resources.displayMetrics.density
    private var shown = false
    private var progress = 0f
    private var baseY = 0f
    private var animator: ValueAnimator? = null
    private var lastTopPosition = RecyclerView.NO_POSITION
    private var topDayPosition = RecyclerView.NO_POSITION
    private var hiddenDivider: View? = null
    private val hideRunnable = Runnable { setShown(false) }

    init {
        // Theme attributes in drawable XML need API 21.
        view.backgroundCompat = GradientDrawable().apply {
            cornerRadius = density * 100
            setColor(MaterialColors.getColor(view, im.vector.lib.ui.styles.R.attr.vctr_floating_date_background))
        }
        render()
    }

    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        view.removeCallbacks(hideRunnable)
        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
            view.postDelayed(hideRunnable, HIDE_DELAY_MS)
        } else {
            lastTopPosition = RecyclerView.NO_POSITION
            update(recyclerView)
        }
    }

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        // Layout passes (new messages, rebuilds) also report scrolls.
        if (recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE) return
        update(recyclerView)
    }

    private fun update(recyclerView: RecyclerView) {
        // Reversed layout: the top row has the highest position.
        val top = layoutManager.findLastVisibleItemPosition()
        val first = layoutManager.findFirstVisibleItemPosition()
        if (top == RecyclerView.NO_POSITION || first == RecyclerView.NO_POSITION) return
        var dayChanged = false
        if (top != lastTopPosition) {
            lastTopPosition = top
            topDayPosition = findDividerAtOrAbove(top)
            if (topDayPosition != RecyclerView.NO_POSITION) {
                dayChanged = setText((adapter.getModelAtPosition(topDayPosition) as DaySeparatorItem).formattedDay)
            }
        }
        val topView = layoutManager.findViewByPosition(top)
        val restY = if (obstruction.isVisible) {
            (obstruction.bottom - view.top + density * GAP_DP).coerceAtLeast(0f)
        } else {
            0f
        }
        val restBottom = view.top + restY + view.height
        if (topDayPosition == RecyclerView.NO_POSITION || topView == null || recyclerView.top + topView.top >= restBottom) {
            setShown(false)
            return
        }
        baseY = restY
        for (position in top - 1 downTo first) {
            if (adapter.getModelAtPosition(position) !is DaySeparatorItem) continue
            val divider = layoutManager.findViewByPosition(position) ?: break
            val overlap = restBottom + density * GAP_DP - (recyclerView.top + divider.top)
            if (overlap > 0) baseY = restY - overlap
            break
        }
        setHiddenDivider(if (topDayPosition == top) topView else null)
        if (shown && dayChanged) {
            progress = 0f
            animateTo(1f)
        } else {
            setShown(true)
        }
        render()
    }

    private fun findDividerAtOrAbove(position: Int): Int {
        for (p in position until adapter.itemCount) {
            if (adapter.getModelAtPosition(p) is DaySeparatorItem) return p
        }
        return RecyclerView.NO_POSITION
    }

    private fun setText(text: String): Boolean {
        if (view.text.toString() == text) return false
        view.text = text
        // onScrolled can run mid-layout, which swallows the resize request.
        view.post { view.requestLayout() }
        return true
    }

    private fun setHiddenDivider(divider: View?) {
        if (divider === hiddenDivider) return
        hiddenDivider?.alpha = 1f
        hiddenDivider = divider
    }

    private fun setShown(show: Boolean) {
        if (show == shown) return
        shown = show
        animateTo(if (show) 1f else 0f)
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = FADE_MS
            addUpdateListener {
                progress = it.animatedValue as Float
                render()
            }
            if (target == 0f) {
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (progress == 0f) setHiddenDivider(null)
                    }
                })
            }
            start()
        }
    }

    private fun render() {
        view.alpha = progress
        view.translationY = baseY - (1 - progress) * density * SLIDE_DP
        hiddenDivider?.alpha = 1 - progress
        view.visibility = if (shown || progress > 0f) View.VISIBLE else View.INVISIBLE
    }

    fun release() {
        view.removeCallbacks(hideRunnable)
        animator?.cancel()
        setHiddenDivider(null)
    }

    private companion object {
        const val HIDE_DELAY_MS = 1000L
        const val FADE_MS = 200L
        const val SLIDE_DP = 8
        const val GAP_DP = 8
    }
}
