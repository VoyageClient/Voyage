/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.RelativeLayout
import androidx.core.graphics.drawable.DrawableCompat

/**
 * Timeline row root. Drops the focused/selected/activated states a held text selection merges up
 * through addStatesFromChildren, which would otherwise sit as a steady selectableItemBackground
 * veil for the selection's whole lifetime.
 *
 * The pressed feedback is drawn as an overlay ON TOP of the row's content rather than as the view
 * background: any opaque paint inside the message (table header fill, code-block panel, media)
 * would punch a hole in a background ripple, leaving those regions visibly dead during the tap.
 */
open class SelectionAwareRelativeLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
) : RelativeLayout(context, attrs, defStyleAttr) {

    private var descendantPressed = false
    private var mergingWindowFocus = false

    private val pressOverlay: Drawable? = context.obtainStyledAttributes(
            intArrayOf(android.R.attr.selectableItemBackground)
    ).let { a ->
        val drawable = a.getDrawable(0)
        a.recycle()
        drawable
    }?.also { it.callback = this }

    /**
     * Pressed feed for descendants that handle their own taps: their pressed state never reaches
     * the addStatesFromChildren merge, so the overlay has to be driven directly. State-only — View
     * pressed flags stay untouched, so nothing cascades back down the tree.
     */
    fun setDescendantPressed(pressed: Boolean, hotspotX: Float, hotspotY: Float) {
        if (descendantPressed == pressed) return
        descendantPressed = pressed
        if (pressed) pressOverlay?.let { DrawableCompat.setHotspot(it, hotspotX, hotspotY) }
        refreshDrawableState()
    }

    /**
     * A window focus change refreshes every descendant's drawable state in turn, and
     * addStatesFromChildren turns each of those into a re-merge of the whole row. Merge once instead,
     * when the dispatch is over; opening a dialog over the timeline otherwise costs hundreds of ms.
     */
    override fun dispatchWindowFocusChanged(hasFocus: Boolean) {
        mergingWindowFocus = true
        try {
            super.dispatchWindowFocusChanged(hasFocus)
        } finally {
            mergingWindowFocus = false
        }
        refreshDrawableState()
    }

    override fun childDrawableStateChanged(child: View) {
        if (mergingWindowFocus) return
        super.childDrawableStateChanged(child)
    }

    override fun verifyDrawable(who: Drawable): Boolean {
        return super.verifyDrawable(who) || who === pressOverlay
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        pressOverlay?.let {
            if (it.isStateful && it.setState(drawableState)) invalidate()
        }
    }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        pressOverlay?.jumpToCurrentState()
    }

    override fun drawableHotspotChanged(x: Float, y: Float) {
        super.drawableHotspotChanged(x, y)
        pressOverlay?.let { DrawableCompat.setHotspot(it, x, y) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pressOverlay?.setBounds(0, 0, w, h)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        pressOverlay?.draw(canvas)
    }

    override fun onDetachedFromWindow() {
        setDescendantPressed(false, 0f, 0f)
        jumpDrawablesToCurrentState()
        super.onDetachedFromWindow()
    }

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val states = super.onCreateDrawableState(extraSpace).filterTo(mutableListOf()) {
            it != android.R.attr.state_focused &&
                    it != android.R.attr.state_selected &&
                    it != android.R.attr.state_activated
        }
        if (descendantPressed && !states.contains(android.R.attr.state_pressed)) {
            states.add(android.R.attr.state_pressed)
        }
        return states.toIntArray()
    }
}
