/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions

import android.content.Context
import android.content.ContextWrapper
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.LinearLayout
import im.vector.app.R
import im.vector.app.core.platform.VectorBaseActivity

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
    private var frozenHeight = 0
    private var frozenWidth = 0

    // Always through requestLayout(): a measure we answered while frozen sits in the view's measure cache,
    // and without the forced pass a later resize can be served that stale height instead of remeasuring.
    private val clearFreeze = Runnable {
        frozenHeight = 0
        requestLayout()
    }

    /** Whether the window lost focus because the app is going away, rather than to a window of our own. */
    internal var isAppLeaving: () -> Boolean = { hostActivity()?.isTopResumedActivity == false }

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

    /**
     * Leaving the app dismisses the keyboard, and the window grows back to full height while the keyboard is
     * still drawn over it for the rest of the app-switcher animation. Hold the height we have until focus
     * returns instead; the keyboard is restored with it, so nothing moves either way. A window of our own
     * taking focus (a dialog, a bottom sheet) is a real dismissal and still gives the space back.
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        when {
            hasWindowFocus -> unfreezeHeightWhenSettled()
            isAppLeaving() -> freezeHeight()
        }
    }

    private fun hostActivity(): VectorBaseActivity<*>? {
        var candidate = context
        while (candidate is ContextWrapper) {
            if (candidate is VectorBaseActivity<*>) return candidate
            candidate = candidate.baseContext
        }
        return null
    }

    private fun freezeHeight() {
        removeCallbacks(clearFreeze)
        if (height > 0) {
            frozenHeight = height
            frozenWidth = width
        }
    }

    /** Ends once the window really is that height again (the keyboard came back), or after the grace period. */
    private fun unfreezeHeightWhenSettled() {
        if (frozenHeight == 0) return
        removeCallbacks(clearFreeze)
        postDelayed(clearFreeze, FREEZE_GRACE_MS)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Rotation or a multi-window resize: the old full height means nothing now.
        if (w != oldw) unshrunkHeight = 0
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (frozenHeight != 0) {
            val widthChanged = MeasureSpec.getSize(widthMeasureSpec) != frozenWidth
            // Settled: the window really is the height we are holding, so letting go changes nothing on screen.
            if (widthChanged || MeasureSpec.getSize(heightMeasureSpec) == frozenHeight) {
                if (widthChanged) frozenHeight = 0
                removeCallbacks(clearFreeze)
                post(clearFreeze)
            }
        }
        val heightSpec = if (frozenHeight != 0) MeasureSpec.makeMeasureSpec(frozenHeight, MeasureSpec.EXACTLY) else heightMeasureSpec
        val available = MeasureSpec.getSize(heightSpec)
        if (available > unshrunkHeight) unshrunkHeight = available
        val takenByKeyboard = (unshrunkHeight - available).coerceAtLeast(0)
        val height = (desiredStripHeight - takenByKeyboard).coerceAtLeast(0)
        // In-place so this measure pass uses it; assigning layoutParams would schedule another one.
        stripView?.layoutParams?.let { if (it.height != height) it.height = height }
        super.onMeasure(widthMeasureSpec, heightSpec)
    }

    companion object {
        private const val FREEZE_GRACE_MS = 600L
    }
}
