/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.RelativeLayout
import androidx.core.content.ContextCompat
import im.vector.app.R

/**
 * Timeline row root that draws the tap flash above its children — android:foreground is
 * FrameLayout-only pre-23, and a background flash gets punched out by opaque children (table
 * headers, media). Pressed feeds in from the addStatesFromChildren merge and from
 * [setFlashPressed]; the drawable never sees the merged focused state, which a held text
 * selection keeps for its whole lifetime and would show as a steady veil.
 */
class TapFlashRelativeLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
) : RelativeLayout(context, attrs, defStyleAttr) {

    // Nullable: the super-constructor's attribute pass calls the drawable-state overrides below
    // before this initializer has run
    private var flash: Drawable? = ContextCompat.getDrawable(context, R.drawable.row_tap_flash)!!.mutate().also {
        it.callback = this
    }

    private var childPressed = false

    // The flash is only ever visible mid-tap. Drawing a (masked, compositing) ripple over the full
    // row every frame regardless would cost every idle row in a scroll a needless offscreen pass —
    // so draw it only while pressed or during its fade-out, gated by this flag.
    private var flashActive = false
    private val stopFlash = Runnable {
        flashActive = false
        invalidate()
    }

    /**
     * State-only pressed feed for clickable descendants, whose pressed never reaches the state
     * merge. Never touches View pressed flags, so nothing cascades back down the tree.
     */
    fun setFlashPressed(pressed: Boolean) {
        if (childPressed != pressed) {
            childPressed = pressed
            syncFlashState()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        flash?.setBounds(0, 0, w, h)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        syncFlashState()
    }

    private fun syncFlashState() {
        val flash = flash ?: return
        val pressed = childPressed || drawableState.any { it == android.R.attr.state_pressed }
        val changed = flash.setState(if (pressed) STATE_PRESSED else STATE_DEFAULT)
        if (pressed) {
            removeCallbacks(stopFlash)
            flashActive = true
        } else if (flashActive) {
            // Keep drawing through the ripple's exit fade, then stop
            removeCallbacks(stopFlash)
            postDelayed(stopFlash, FLASH_FADE_MS)
        }
        if (changed) invalidate()
    }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        flash?.jumpToCurrentState()
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === flash || super.verifyDrawable(who)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        flash?.setVisible(true, false)
    }

    override fun onDetachedFromWindow() {
        childPressed = false
        removeCallbacks(stopFlash)
        flashActive = false
        syncFlashState()
        flash?.jumpToCurrentState()
        flash?.setVisible(false, false)
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (flashActive) flash?.draw(canvas)
    }

    companion object {
        private const val FLASH_FADE_MS = 300L
        private val STATE_PRESSED = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed)
        private val STATE_DEFAULT = intArrayOf(android.R.attr.state_enabled)
    }
}
