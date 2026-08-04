/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.util.AttributeSet
import android.widget.RelativeLayout
import androidx.core.graphics.drawable.DrawableCompat

/**
 * Timeline row root. Drops the focused/selected/activated states a held text selection merges up
 * through addStatesFromChildren, which would otherwise sit as a steady selectableItemBackground
 * veil for the selection's whole lifetime.
 */
open class SelectionAwareRelativeLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
) : RelativeLayout(context, attrs, defStyleAttr) {

    private var descendantPressed = false

    /**
     * Pressed feed for descendants that handle their own taps: their pressed state never reaches
     * the addStatesFromChildren merge, so the ripple has to be driven directly. State-only — View
     * pressed flags stay untouched, so nothing cascades back down the tree.
     */
    fun setDescendantPressed(pressed: Boolean, hotspotX: Float, hotspotY: Float) {
        if (descendantPressed == pressed) return
        descendantPressed = pressed
        if (pressed) background?.let { DrawableCompat.setHotspot(it, hotspotX, hotspotY) }
        refreshDrawableState()
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
