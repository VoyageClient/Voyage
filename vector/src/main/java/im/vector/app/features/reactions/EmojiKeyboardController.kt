/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.PopupWindow
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the inline emoji + custom-emote keyboard. To occupy the keyboard region without hiding the
 * composer, it keeps the soft keyboard *open* (so the window stays resized and the composer stays
 * visible above) and lays a non-focusable [PopupWindow] over it. Dismissing reveals the keyboard again.
 */
class EmojiKeyboardController(
        private val activity: Activity,
        private val rootView: View,
        private val editText: EditText,
        private val roomId: String?,
        private val sectionFactory: EmojiPickerSectionFactory,
        private val scope: CoroutineScope,
        private val onVisibilityChanged: (visible: Boolean) -> Unit,
) {

    private val keyboardView = EmojiPickerView(activity).apply {
        onEmojiClick = EmojiPickerView.OnEmojiClickListener { insert(it) }
    }
    private val popup = PopupWindow(keyboardView, ViewGroup.LayoutParams.MATCH_PARENT, 0).apply {
        setBackgroundDrawable(ColorDrawable(0))
        // Non-focusable + no input method, so the soft keyboard behind stays "open" (window resized,
        // composer visible) and we just draw over it.
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        isFocusable = false
        isOutsideTouchable = false
        // The keyboard region (and the measured height) includes the nav-bar area; let the panel extend
        // into it so Gravity.BOTTOM reaches the true screen bottom instead of the app content bottom,
        // otherwise the panel rides ~nav-bar-height too high (overlapping the composer and exposing the
        // keyboard's bottom row).
        isClippingEnabled = false
    }
    private val heightProvider = KeyboardHeightProvider(activity)
    private var lastKeyboardHeight = 0
    private var currentKeyboardHeight = 0
    private var backspaceHeld = false
    private var pendingShow = false
    private var reloadJob: Job? = null
    private val closeRunnable = Runnable {
        lastKeyboardHeight = 0
        if (popup.isShowing) dismiss()
    }

    val isShowing: Boolean get() = popup.isShowing

    init {
        keyboardView.setTrailingAction(
                im.vector.app.R.drawable.ic_backspace,
                im.vector.lib.strings.CommonStrings.action_delete,
                onPressChanged = { pressed ->
                    backspaceHeld = pressed
                    if (!pressed && popup.isShowing && currentKeyboardHeight <= MIN_KEYBOARD_HEIGHT) scheduleClose()
                },
        ) { backspace() }
        heightProvider.onKeyboardHeightChanged = { height ->
            currentKeyboardHeight = height
            if (height > MIN_KEYBOARD_HEIGHT) {
                rootView.removeCallbacks(closeRunnable)
                lastKeyboardHeight = height
                if (pendingShow) {
                    pendingShow = false
                    showPopup(height)
                } else if (popup.isShowing) {
                    popup.update(ViewGroup.LayoutParams.MATCH_PARENT, height)
                }
            } else if (popup.isShowing) {
                scheduleClose()
            } else {
                lastKeyboardHeight = 0
            }
        }
        rootView.post { heightProvider.start() }
    }

    // The IME restarts (briefly reporting height 0) on each edit, so only treat a sustained dip as a real close
    // (back button). A held backspace restarts it faster than any debounce, so wait until the key is released.
    private fun scheduleClose() {
        rootView.removeCallbacks(closeRunnable)
        if (backspaceHeld) return
        rootView.postDelayed(closeRunnable, KEYBOARD_CLOSE_DEBOUNCE_MS)
    }

    fun toggle() {
        if (popup.isShowing || pendingShow) dismiss() else show()
    }

    private fun show() {
        // Rebuild each time so pack enable/disable (and edits) reflect without re-entering the room.
        reload()
        if (lastKeyboardHeight > MIN_KEYBOARD_HEIGHT) {
            showPopup(lastKeyboardHeight)
        } else {
            // Keyboard not open yet: open it (so the window resizes and the composer floats up), then
            // show the panel over it once we know its height.
            pendingShow = true
            editText.requestFocus()
            activity.getSystemService<InputMethodManager>()?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun showPopup(height: Int) {
        keyboardView.setBottomInset(heightProvider.navigationBarHeight)
        popup.height = height
        popup.showAtLocation(rootView, Gravity.BOTTOM, 0, 0)
        onVisibilityChanged(true)
    }

    fun dismiss() {
        pendingShow = false
        if (popup.isShowing) {
            popup.dismiss()
            onVisibilityChanged(false)
        }
    }

    /** Fully close: dismiss the panel and the soft keyboard. */
    fun close() {
        dismiss()
        activity.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(editText.windowToken, 0)
    }

    fun destroy() {
        rootView.removeCallbacks(closeRunnable)
        if (popup.isShowing) popup.dismiss()
        heightProvider.close()
    }

    fun prewarm() = reload()

    private fun reload() {
        if (reloadJob?.isActive == true) return
        reloadJob = scope.launch {
            // Off-main: the pack aggregation walks account data + room state + the space hierarchy, and the
            // emoji categories build ~1800 items. Safe there — each SDK read opens its own Realm.
            val sections = withContext(Dispatchers.Default) { sectionFactory.build(roomId) }
            keyboardView.setSections(sections)
        }
    }

    private fun insert(item: EmojiPickerItem) {
        val text = when (item) {
            is EmojiPickerItem.Unicode -> item.glyph
            is EmojiPickerItem.Emote -> ":${item.shortcode}:"
        }
        val start = editText.selectionStart.coerceAtLeast(0)
        val end = editText.selectionEnd.coerceAtLeast(0)
        val from = minOf(start, end)
        editText.text.replace(from, maxOf(start, end), text)
        editText.setSelection((from + text.length).coerceAtMost(editText.text.length))
        sectionFactory.recordUse(
                when (item) {
                    is EmojiPickerItem.Unicode -> item.glyph
                    is EmojiPickerItem.Emote -> item.key
                }
        )
    }

    private fun backspace() {
        // Emulate a hardware Backspace through the key pipeline rather than mutating the Editable
        // directly: a direct edit makes the IME resync/restart, which flashes the soft keyboard in
        // behind the panel when the key is held down. The editor's key listener also deletes a whole
        // grapheme (incl. an emoji surrogate pair), or the current selection, for us.
        val before = editText.text.length
        editText.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        editText.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        if (editText.text.length != before) return

        // Fallback if the key event didn't land (e.g. the field lost focus): delete directly.
        val end = editText.selectionEnd.coerceAtLeast(0)
        val start = editText.selectionStart.coerceAtLeast(0)
        if (start != end) {
            editText.text.delete(minOf(start, end), maxOf(start, end))
        } else if (end > 0) {
            val text = editText.text
            val deleteFrom = if (end >= 2 && Character.isLowSurrogate(text[end - 1]) && Character.isHighSurrogate(text[end - 2])) end - 2 else end - 1
            text.delete(deleteFrom, end)
        }
    }

    companion object {
        private const val MIN_KEYBOARD_HEIGHT = 150
        private const val KEYBOARD_CLOSE_DEBOUNCE_MS = 250L
    }
}
