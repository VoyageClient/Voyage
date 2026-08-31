/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions

import android.app.Activity
import android.content.Context
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the inline emoji + custom-emote keyboard.
 *
 * The panel is a strip of the room screen itself, not a window over the keyboard: while either the panel
 * or the soft keyboard is up, [EmojiPanelHostLayout] reserves the keyboard's footprint below the composer
 * and hands back whatever the window has already given the IME. Swapping one for the other, or opening
 * the panel with no keyboard up, then moves the composer in the same layout pass rather than a beat late.
 */
class EmojiKeyboardController(
        private val activity: Activity,
        private val panelHost: EmojiPanelHostLayout,
        private val editText: EditText,
        private val roomId: String?,
        private val sectionFactory: EmojiPickerSectionFactory,
        private val scope: CoroutineScope,
        private val onVisibilityChanged: (visible: Boolean) -> Unit,
) {

    private val pickerView = EmojiPickerView(activity).apply {
        onEmojiClick = EmojiPickerView.OnEmojiClickListener { insert(it) }
        setSearchEnabled(false)
    }
    private val heightProvider = KeyboardHeightProvider(activity)
    private val prefs = activity.getSharedPreferences("emoji_panel", Context.MODE_PRIVATE)
    private var lastKeyboardHeight = 0
    private var backspaceHeld = false
    private var stripReserved = false
    private var panelOpen = false
    private var reloadJob: Job? = null

    val isShowing: Boolean get() = panelOpen

    init {
        pickerView.setTrailingAction(
                im.vector.app.R.drawable.ic_backspace,
                im.vector.lib.strings.CommonStrings.action_delete,
                onPressChanged = { pressed -> backspaceHeld = pressed },
        ) { backspace() }
        pickerView.isVisible = false
        panelHost.strip.addView(pickerView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        heightProvider.onKeyboardHeightChanged = { height ->
            // Every delete restarts the IME, which reports a burst of transient heights (including a
            // momentary 0) as its window tears down and comes back; a held backspace repeats faster than
            // any debounce, so leave the strip alone until the key comes up.
            if (!backspaceHeld) onKeyboardHeight(height)
        }
        panelHost.post { heightProvider.start() }
    }

    private fun onKeyboardHeight(height: Int) {
        if (height > MIN_KEYBOARD_HEIGHT) {
            if (height != lastKeyboardHeight) prefs.edit { putInt(PREF_KEYBOARD_HEIGHT, height) }
            lastKeyboardHeight = height
            if (stripReserved) panelHost.setDesiredStripHeight(stripHeight())
            // The panel stays drawn until the keyboard is actually over it, so the strip is never a hole.
            if (!panelOpen) pickerView.isVisible = false
        } else if (stripReserved && !panelOpen) {
            // Keyboard gone with no panel to take its place: give the space back to the timeline.
            pickerView.isVisible = false
            releaseStrip()
        }
    }

    fun toggle() {
        if (isShowing) dismiss() else show()
    }

    private fun show() {
        // Rebuild each time so pack enable/disable (and edits) reflect without re-entering the room.
        reload()
        panelOpen = true
        reserveStrip()
        pickerView.isVisible = true
        hideKeyboard()
        onVisibilityChanged(true)
    }

    /** Hides the panel and hands the space straight to the keyboard, which draws over the same strip. */
    fun dismiss() {
        if (!panelOpen) return
        panelOpen = false
        // Left drawn until the IME reports in: the keyboard slides in over it instead of over a gap.
        showKeyboardOnComposer()
        onVisibilityChanged(false)
    }

    /** Fully close: panel, keyboard, and the space they shared. */
    fun close() {
        val wasShowing = panelOpen
        panelOpen = false
        pickerView.isVisible = false
        hideKeyboard()
        releaseStrip()
        if (wasShowing) onVisibilityChanged(false)
    }

    fun destroy() {
        panelOpen = false
        pickerView.isVisible = false
        releaseStrip()
        panelHost.strip.removeView(pickerView)
        heightProvider.close()
    }

    fun prewarm() = reload()

    /**
     * The keyboard's footprint inside the app window (it also covers the system bar, the strip cannot).
     * What the window has already surrendered to the IME is subtracted by the host at measure time.
     */
    private fun stripHeight(): Int {
        val keyboardHeight = lastKeyboardHeight.takeIf { it > MIN_KEYBOARD_HEIGHT } ?: rememberedKeyboardHeight()
        return (keyboardHeight - heightProvider.navigationBarHeight).coerceAtLeast(MIN_KEYBOARD_HEIGHT)
    }

    private fun rememberedKeyboardHeight(): Int {
        val screenHeight = heightProvider.screenHeight()
        return prefs.getInt(PREF_KEYBOARD_HEIGHT, (screenHeight * DEFAULT_KEYBOARD_FRACTION).toInt())
                .coerceIn(MIN_KEYBOARD_HEIGHT, (screenHeight * MAX_KEYBOARD_FRACTION).toInt())
    }

    private fun reserveStrip() {
        stripReserved = true
        panelHost.setDesiredStripHeight(stripHeight())
    }

    private fun releaseStrip() {
        if (!stripReserved) return
        stripReserved = false
        panelHost.setDesiredStripHeight(0)
    }

    private fun hideKeyboard() {
        activity.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(editText.windowToken, 0)
    }

    private fun showKeyboardOnComposer() {
        editText.requestFocus()
        activity.getSystemService<InputMethodManager>()?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun reload() {
        if (reloadJob?.isActive == true) return
        reloadJob = scope.launch {
            // Off-main: the pack aggregation walks account data + room state + the space hierarchy, and the
            // emoji categories build ~1800 items. Safe there — each SDK read opens its own Realm.
            val sections = withContext(Dispatchers.Default) { sectionFactory.build(roomId) }
            pickerView.setSections(sections)
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
        private const val PREF_KEYBOARD_HEIGHT = "keyboard_height"
        private const val DEFAULT_KEYBOARD_FRACTION = 0.4f
        private const val MAX_KEYBOARD_FRACTION = 0.6f
    }
}
