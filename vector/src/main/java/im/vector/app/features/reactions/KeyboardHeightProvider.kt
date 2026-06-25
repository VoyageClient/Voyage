/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions

import android.app.Activity
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.PopupWindow

/**
 * Measures the on-screen soft-keyboard height across all API levels using the classic invisible-popup
 * trick (a 0-width popup that fills the window height; the difference between the window and its visible
 * frame is the keyboard height). Works pre-Lollipop, unlike WindowInsets-based detection.
 */
class KeyboardHeightProvider(private val activity: Activity) : PopupWindow(activity) {

    var onKeyboardHeightChanged: ((height: Int) -> Unit)? = null

    // The bottom system bar height (soft nav bar / gesture home bar), measured device-agnostically: when
    // the keyboard is closed, (screenHeight - visibleFrame.bottom) is exactly that bar. 0 on hardware-key
    // devices. Used to inset the panel content above the bar without relying on API 23+ WindowInsets.
    var navigationBarHeight = 0
        private set

    private val popupView = View(activity)
    private val parentView: View = activity.findViewById(android.R.id.content)
    private var lastHeight = -1

    init {
        contentView = popupView
        @Suppress("DEPRECATION")
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        inputMethodMode = INPUT_METHOD_NEEDED
        width = 0
        height = ViewGroup.LayoutParams.MATCH_PARENT
        setBackgroundDrawable(ColorDrawable(0))
        popupView.viewTreeObserver.addOnGlobalLayoutListener { handleOnGlobalLayout() }
    }

    fun start() {
        if (!isShowing && parentView.windowToken != null) {
            showAtLocation(parentView, Gravity.NO_GRAVITY, 0, 0)
        }
    }

    fun close() {
        onKeyboardHeightChanged = null
        dismiss()
    }

    private fun handleOnGlobalLayout() {
        val rect = Rect()
        popupView.getWindowVisibleDisplayFrame(rect)
        // The popup is 0-width, so its own rootView height is NOT the screen height — use the real
        // display height, otherwise (screenHeight - rect.bottom) goes negative and is never detected.
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenHeight = metrics.heightPixels
        val keyboardHeight = screenHeight - rect.bottom
        // Keyboard closed: what's left below the visible frame is just the bottom system bar.
        if (keyboardHeight in 0 until KEYBOARD_OPEN_THRESHOLD) {
            navigationBarHeight = keyboardHeight
        }
        if (keyboardHeight != lastHeight) {
            lastHeight = keyboardHeight
            onKeyboardHeightChanged?.invoke(keyboardHeight)
        }
    }

    companion object {
        private const val KEYBOARD_OPEN_THRESHOLD = 150
    }
}
