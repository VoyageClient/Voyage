/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.utils.KeyboardDebug
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
        private val onKeyboardDismissed: () -> Unit,
        // Unlike onVisibilityChanged, follows the panel staying drawn until the keyboard covers it.
        private val onPanelDrawnChanged: (drawn: Boolean) -> Unit,
) {

    private val pickerView = EmojiPickerView(activity).apply {
        onEmojiClick = EmojiPickerView.OnEmojiClickListener { insert(it) }
        setSearchEnabled(false)
    }
    private val heightProvider = KeyboardHeightProvider(activity)
    private val prefs = activity.getSharedPreferences("emoji_panel", Context.MODE_PRIVATE)
    private var lastKeyboardHeight = 0
    private var keyboardVisible = false
    private var paused = false
    private var showKeyboardOnResume = false
    private var keyboardRestoreSuppressed = false
    private var restoreKeyboardOnNextResume = false
    private val hideKeyboardAfterPause = Runnable { if (paused) hideKeyboard() }
    private val restoreKeyboardAfterFocus = Runnable { restoreKeyboardIfReady() }
    private val releaseKeyboardRestoreHold = Runnable {
        hostActivity?.isRestoringComposerKeyboard = false
        setAutomaticKeyboardRestore(keyboardVisible)
        panelHost.releaseSettledHeightHold()
    }

    private var keyboardDismissalPending = false
    private var backspaceHeld = false
    private var stripReserved = false
    private var panelOpen = false
    private var reloadJob: Job? = null
    private val hostActivity = activity as? VectorBaseActivity<*>
    private val usesNativeImeState = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hostActivity != null
    private val imeStateListener: (Int, Boolean) -> Unit = { height, animating ->
        if (!animating) {
            val measuredHeight = heightProvider.currentKeyboardHeight
            updateKeyboardVisibility(if (height > 0 && measuredHeight > MIN_KEYBOARD_HEIGHT) measuredHeight else height)
        }
    }

    private val recordKeyboardDismissal = object : Runnable {
        override fun run() {
            if (!keyboardDismissalPending || keyboardVisible || panelOpen) return
            // A system overlay or cancelled gesture can temporarily hide the IME.
            if (paused || !activity.hasWindowFocus()) return
            if (panelHost.isHeightFrozen || hostActivity?.isImeAnimating == true) {
                editText.postDelayed(this, 100L)
                return
            }
            keyboardDismissalPending = false
            KeyboardDebug.log { "EmojiKbd recordKeyboardDismissal -> rememberKeyboardDismissed ${KeyboardDebug.state(editText)}" }
            rememberKeyboardDismissed()
        }
    }

    val isShowing: Boolean get() = panelOpen

    init {
        if (usesNativeImeState) hostActivity?.addImeStateListener(imeStateListener)
        panelHost.onWindowFocusRestored = {
            // Sheets and dialogs take focus without pausing the activity.
            if (!paused) consumeRestoreOnReturn()
            // The input method receives window focus after the view hierarchy does.
            editText.removeCallbacks(restoreKeyboardAfterFocus)
            editText.post(restoreKeyboardAfterFocus)
            if (keyboardDismissalPending) {
                editText.removeCallbacks(recordKeyboardDismissal)
                editText.postDelayed(recordKeyboardDismissal, 200L)
            }
        }
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
        if (usesNativeImeState) hostActivity?.updateMeasuredComposerImeHeight(height)
        else updateKeyboardVisibility(height)
        if (height > MIN_KEYBOARD_HEIGHT) {
            if (height != lastKeyboardHeight) prefs.edit { putInt(PREF_KEYBOARD_HEIGHT, height) }
            lastKeyboardHeight = height
            if (stripReserved) panelHost.setDesiredStripHeight(stripHeight())
            // The panel stays drawn until the keyboard is actually over it, so the strip is never a hole.
            if (!panelOpen) setPanelDrawn(false)
        } else if (stripReserved && !panelOpen) {
            // Keyboard gone with no panel to take its place: give the space back to the timeline.
            setPanelDrawn(false)
            releaseStrip()
        }
    }

    private fun updateKeyboardVisibility(height: Int) {
        val wasVisible = keyboardVisible
        keyboardVisible = height > MIN_KEYBOARD_HEIGHT
        if (wasVisible != keyboardVisible) {
            KeyboardDebug.log {
                "EmojiKbd visibility $wasVisible->$keyboardVisible height=$height panelOpen=$panelOpen paused=$paused " +
                        "suppressed=$keyboardRestoreSuppressed ${KeyboardDebug.state(editText)}"
            }
        }
        if (keyboardVisible && editText.hasFocus()) keyboardRestoreSuppressed = false
        if (keyboardVisible || panelOpen) {
            if (keyboardVisible && !paused && !keyboardRestoreSuppressed) {
                setAutomaticKeyboardRestore(true)
                if (hostActivity?.isRestoringComposerKeyboard == true && activity.hasWindowFocus()) {
                    editText.removeCallbacks(releaseKeyboardRestoreHold)
                    releaseKeyboardRestoreHold.run()
                }
            }
            keyboardDismissalPending = false
            editText.removeCallbacks(recordKeyboardDismissal)
        }
        if (wasVisible && !keyboardVisible && !panelOpen) {
            keyboardDismissalPending = true
            editText.postDelayed(recordKeyboardDismissal, 200L)
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
        setPanelDrawn(true)
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
        KeyboardDebug.log { "EmojiKbd close panelOpen=$panelOpen caller=${KeyboardDebug.caller()}" }
        val wasShowing = panelOpen
        keyboardRestoreSuppressed = true
        keyboardVisible = false
        keyboardDismissalPending = false
        editText.removeCallbacks(recordKeyboardDismissal)
        editText.removeCallbacks(restoreKeyboardAfterFocus)
        editText.removeCallbacks(releaseKeyboardRestoreHold)
        hostActivity?.isRestoringComposerKeyboard = false
        panelOpen = false
        setPanelDrawn(false)
        hideKeyboard()
        rememberKeyboardDismissed()
        releaseStrip()
        if (wasShowing) onVisibilityChanged(false)
    }

    fun destroy() {
        editText.removeCallbacks(releaseKeyboardRestoreHold)
        hostActivity?.isRestoringComposerKeyboard = false
        hostActivity?.updateMeasuredComposerImeHeight(0)
        editText.removeCallbacks(restoreKeyboardAfterFocus)
        editText.removeCallbacks(hideKeyboardAfterPause)
        hostActivity?.removeImeStateListener(imeStateListener)
        panelHost.onWindowFocusRestored = null
        editText.removeCallbacks(recordKeyboardDismissal)
        panelOpen = false
        setPanelDrawn(false)
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

    private fun setPanelDrawn(drawn: Boolean) {
        if (pickerView.isVisible == drawn) return
        pickerView.isVisible = drawn
        onPanelDrawnChanged(drawn)
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

    private fun rememberKeyboardDismissed() {
        showKeyboardOnResume = false
        onKeyboardDismissed()
        setAutomaticKeyboardRestore(false)
    }

    private fun setAutomaticKeyboardRestore(enabled: Boolean) {
        val state = if (enabled) WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED else WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        setKeyboardRestoreState(state)
    }

    private fun setKeyboardRestoreState(state: Int) {
        val mode = activity.window.attributes.softInputMode
        val updated = (mode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE.inv()) or state
        if (mode != updated) {
            KeyboardDebug.log { "EmojiKbd softInput ${KeyboardDebug.softInputMode(mode)}->${KeyboardDebug.softInputMode(updated)} caller=${KeyboardDebug.caller()}" }
            activity.window.setSoftInputMode(updated)
        }
    }

    fun onPause() {
        KeyboardDebug.log { "EmojiKbd onPause keyboardVisible=$keyboardVisible ${KeyboardDebug.state(editText)}" }
        editText.removeCallbacks(restoreKeyboardAfterFocus)
        paused = true
        // A restore still waiting for window focus (e.g. a permission prompt chained into a picker) must survive.
        showKeyboardOnResume = showKeyboardOnResume || !keyboardRestoreSuppressed && keyboardVisible &&
                (editText.hasFocus() || activity.currentFocus?.onCheckIsTextEditor() != true)
        editText.removeCallbacks(releaseKeyboardRestoreHold)
        hostActivity?.isRestoringComposerKeyboard = showKeyboardOnResume
        val anotherEditorFocused = !editText.hasFocus() && activity.currentFocus?.onCheckIsTextEditor() == true
        if (!showKeyboardOnResume && !panelOpen && !anotherEditorFocused) rememberKeyboardDismissed()
        editText.removeCallbacks(hideKeyboardAfterPause)
        editText.postDelayed(hideKeyboardAfterPause, 500L)
    }

    fun restoreKeyboardOnReturn() {
        restoreKeyboardOnNextResume = true
    }

    fun onResume() {
        KeyboardDebug.log { "EmojiKbd onResume showKeyboardOnResume=$showKeyboardOnResume ${KeyboardDebug.state(editText)}" }
        paused = false
        editText.removeCallbacks(hideKeyboardAfterPause)
        consumeRestoreOnReturn()
        if (showKeyboardOnResume) setKeyboardRestoreState(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        restoreKeyboardIfReady()
    }

    private fun consumeRestoreOnReturn() {
        if (!restoreKeyboardOnNextResume) return
        restoreKeyboardOnNextResume = false
        keyboardRestoreSuppressed = false
        showKeyboardOnResume = true
    }

    private fun restoreKeyboardIfReady() {
        if (!paused && showKeyboardOnResume && activity.hasWindowFocus()) {
            keyboardDismissalPending = false
            editText.removeCallbacks(recordKeyboardDismissal)
            showKeyboardOnResume = false
            editText.requestFocus()
            val result = activity.getSystemService<InputMethodManager>()?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            KeyboardDebug.log { "EmojiKbd restoreKeyboardIfReady result=$result ${KeyboardDebug.state(editText)}" }
            editText.postDelayed(releaseKeyboardRestoreHold, 1500L)
        }
    }

    private fun hideKeyboard() {
        val result = activity.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(editText.windowToken, 0)
        KeyboardDebug.log { "EmojiKbd hideKeyboard result=$result ${KeyboardDebug.state(editText)} caller=${KeyboardDebug.caller()}" }
    }

    private fun showKeyboardOnComposer() {
        setAutomaticKeyboardRestore(true)
        editText.requestFocus()
        val result = activity.getSystemService<InputMethodManager>()?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        KeyboardDebug.log { "EmojiKbd showKeyboardOnComposer result=$result ${KeyboardDebug.state(editText)}" }
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
