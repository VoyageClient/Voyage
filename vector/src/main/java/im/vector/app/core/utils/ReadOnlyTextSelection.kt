/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import android.os.Build
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.TextView
import im.vector.app.core.ui.views.SelectionAwareRelativeLayout
import im.vector.app.features.html.HtmlCodeSpan
import im.vector.lib.strings.CommonStrings
import timber.log.Timber

/**
 * Trims a selectable (but not editable) TextView's selection menu to Copy / Share / Select all,
 * with a hand-added Share pre-M where the framework has none. In a table cell it also offers
 * "Copy table" (the selection itself never leaves the cell).
 */
class ReadOnlySelectionActionModeCallback(private val textView: TextView) : ActionMode.Callback {

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        filterMenu(menu)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        filterMenu(menu)
        return true
    }

    private fun tableMarkdown(): String? = (textView as? TableSourceProvider)?.tableMarkdownSource()

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.selectAll -> {
                // Break any code-span lock so select-all covers the whole cell's text
                (textView as? CodeSelectionBoundsHost)?.codeSelectionBounds = 0..textView.length()
            }
            android.R.id.copy -> {
                val text = selectedSourceText()
                mode.finish()
                if (text.isNotEmpty()) copyToClipboard(textView.context, text, showToast = false)
                return true
            }
            android.R.id.shareText, SHARE_FALLBACK_ID -> {
                val text = selectedSourceText()
                mode.finish()
                if (text.isNotEmpty()) shareText(textView.context, text)
                return true
            }
            COPY_TABLE_ID -> {
                val text = tableMarkdown()
                mode.finish()
                if (!text.isNullOrEmpty()) copyToClipboard(textView.context, text, showToast = false)
                return true
            }
        }
        return false
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        val host = textView as? SelectionFocusHost
        // Drop the focus the selection gesture took, so no focus highlight lingers on the view
        if (host == null) textView.clearFocus() else host.selectionFocus.endSelection()
    }

    private fun filterMenu(menu: Menu) {
        var i = 0
        while (i < menu.size()) {
            val id = menu.getItem(i).itemId
            if (id in KEEP_IDS) i++ else menu.removeItem(id)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M && menu.findItem(SHARE_FALLBACK_ID) == null) {
            menu.add(Menu.NONE, SHARE_FALLBACK_ID, Menu.CATEGORY_SECONDARY, CommonStrings.action_share)
        }
        if (tableMarkdown() != null && menu.findItem(COPY_TABLE_ID) == null) {
            menu.add(Menu.NONE, COPY_TABLE_ID, Menu.CATEGORY_SECONDARY, CommonStrings.action_copy_table)
        }
    }

    // The rendered chars alone don't round-trip (list markers/quote stripes are drawn, not chars;
    // styling and pills vanish in plain text), so copy/share the reconstructed markdown source.
    private fun selectedSourceText(): String {
        val start = textView.selectionStart.coerceAtLeast(0)
        val end = textView.selectionEnd.coerceAtLeast(0)
        val spanned = textView.text as? Spanned
                ?: return textView.text?.subSequence(minOf(start, end), maxOf(start, end))?.toString().orEmpty()
        return spanned.toMarkdownSource(start, end)
    }

    companion object {
        private const val SHARE_FALLBACK_ID = Menu.FIRST
        private const val COPY_TABLE_ID = Menu.FIRST + 1
        private val KEEP_IDS = setOf(android.R.id.copy, android.R.id.selectAll, android.R.id.shareText, SHARE_FALLBACK_ID)
    }
}

/**
 * Toggles read-only text selection. Note that enabling/disabling replaces the movement method,
 * so callers must (re-)assign theirs afterwards.
 */
fun TextView.setReadOnlySelectable(selectable: Boolean) {
    if (selectable && customSelectionActionModeCallback !is ReadOnlySelectionActionModeCallback) {
        customSelectionActionModeCallback = ReadOnlySelectionActionModeCallback(this)
    }
    if (isTextSelectable != selectable) {
        setTextIsSelectable(selectable)
    }
    if (selectable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        // Selectable = focusable in touch mode; without this the view keeps a white focus veil
        // from the long-press until something else takes focus
        defaultFocusHighlightEnabled = false
    }
}

/** Holds the bounds [clampSelectionToCodeSpans] locked the current selection to. */
interface CodeSelectionBoundsHost {
    var codeSelectionBounds: IntRange?
}

/**
 * Keeps a selection that started in an [HtmlCodeSpan] inside that span; [active] is the previous
 * return value (the bounds the selection is locked to — sticky, so a drag past the edge can't
 * re-anchor onto a neighbouring span). Call from [TextView.onSelectionChanged]. A selection that
 * starts outside any code span gets whole-text bounds and so moves freely (Select all upgrades a
 * span lock to that).
 */
fun TextView.clampSelectionToCodeSpans(active: IntRange?): IntRange? {
    val spannable = text as? Spannable
    val start = minOf(selectionStart, selectionEnd)
    val end = maxOf(selectionStart, selectionEnd)
    val bounds = when {
        !isTextSelectable || spannable == null || start < 0 || start == end -> null
        active != null && active.last <= spannable.length && start < active.last && end > active.first -> active
        else -> spannable.getSpans(0, spannable.length, HtmlCodeSpan::class.java)
                .firstOrNull { spannable.getSpanStart(it) < end && start < spannable.getSpanEnd(it) }
                ?.let { spannable.getSpanStart(it)..spannable.getSpanEnd(it) }
                ?: 0..spannable.length
    }
    var clamped = false
    if (bounds != null && spannable != null) {
        val newStart = start.coerceIn(bounds.first, bounds.last)
        val newEnd = end.coerceIn(bounds.first, bounds.last)
        if (newStart != start || newEnd != end) {
            clamped = true
            Selection.setSelection(spannable, newStart, newEnd)
        }
    }
    // Only while the clamp is fighting a drag past the span edge, where the handle-move haptic
    // would fire per touch event — a vibration storm. In-bounds moves keep their normal ticks.
    isHapticFeedbackEnabled = !clamped
    return bounds
}

/**
 * A tap on a focusable-in-touch-mode view is swallowed by the focus grab (View.onTouchEvent
 * performs focus OR click, not both), killing the tap feedback/behavior on the first touch.
 * Call from onTouchEvent after super with the pre-super focus state to replay the lost click.
 */
fun TextView.replaySwallowedTap(event: MotionEvent, wasFocused: Boolean) {
    if (event.actionMasked == MotionEvent.ACTION_UP && !wasFocused && isFocused && !hasSelection()) {
        performClick()
    }
}

/** Gives [ReadOnlySelectionActionModeCallback] access to the view's [ReadOnlySelectionFocus]. */
interface SelectionFocusHost {
    val selectionFocus: ReadOnlySelectionFocus
}

/**
 * Selectable text is focusable in touch mode, so every plain tap pulls focus off the composer and
 * makes the IME rebind to a view that can't be typed into — the keyboard visibly resets and stays
 * dead until focus comes back. Keep focusability off until a gesture that actually starts a
 * selection needs it (long-press, or the second tap of a double-tap), and hand focus back to the
 * editor it came from once the selection ends. Drive it from the view's touch/long-click overrides.
 */
class ReadOnlySelectionFocus(private val textView: TextView) {

    // The editor focus was taken from; cleared once handed back
    private var focusBeforeSelection: View? = null
    private var lastTapUpTime = 0L

    fun beforeTouch(event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return
        // While we hold focus a selection is in progress, so keep the editor we already recorded
        if (!textView.isFocused) focusBeforeSelection = textView.rootView?.findFocus() as? EditText
        textView.isFocusableInTouchMode = textView.isTextSelectable &&
                event.eventTime - lastTapUpTime < ViewConfiguration.getDoubleTapTimeout()
    }

    fun afterTouch(event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_UP) return
        lastTapUpTime = event.eventTime
        if (!textView.hasSelection()) endSelection()
    }

    fun beforeLongClick() {
        textView.isFocusableInTouchMode = textView.isTextSelectable
    }

    /** Call from onDetachedFromWindow: a recycled view must not arrive focusable, or holding a dead editor. */
    fun detach() {
        focusBeforeSelection = null
        textView.isFocusableInTouchMode = false
    }

    fun endSelection() {
        textView.isFocusableInTouchMode = false
        if (!textView.isFocused) return
        val previousFocus = focusBeforeSelection
        focusBeforeSelection = null
        // Move focus in one step rather than clearing first, so the IME never sees an unfocused window
        if (previousFocus?.requestFocus() != true) textView.clearFocus()
    }
}

/**
 * Feeds a pressed state to the timeline row's ripple, with [x]/[y] (view coordinates) translated
 * into the row's for the hotspot. Call from setPressed after super — a view that handles its own
 * taps never gets its pressed state merged up into the row.
 */
fun View.mirrorPressedToRowRipple(pressed: Boolean, x: Float, y: Float) {
    var view: View = this
    var hotspotX = x
    var hotspotY = y
    while (true) {
        val parent = view.parent as? View ?: return
        hotspotX += view.left - parent.scrollX
        hotspotY += view.top - parent.scrollY
        if (parent is SelectionAwareRelativeLayout) {
            parent.setDescendantPressed(pressed, hotspotX, hotspotY)
            return
        }
        view = parent
    }
}

/**
 * When a selectable-but-not-editable view takes focus, the IME rebinds to a TYPE_NULL dummy and
 * some keyboards switch layout (e.g. grow a number row). Report a plain-text editor instead —
 * pair with `onCheckIsTextEditor() = isTextSelectable` — so an open keyboard keeps its layout;
 * the connection edits nothing and nothing ever calls showSoftInput.
 */
fun TextView.readOnlySelectionInputConnection(outAttrs: EditorInfo): InputConnection {
    outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
    outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    return BaseInputConnection(this, false)
}

/**
 * A starting selection (long-press or double-tap) hijacks the touch stream, so the pressed state
 * — and the row ripple fed by it — can stay stuck mid-animation.
 * Call from onSelectionChanged to release it as soon as a selection exists.
 */
fun TextView.releasePressedRippleOnSelection(selStart: Int, selEnd: Int) {
    if (selStart != selEnd && isPressed) {
        isPressed = false
    }
}

// Some Android 4.x (TouchWiz) builds throw ArithmeticException: divide by zero creating the
// text-selection action mode; swallow it so the menu just fails to open (see ComposerEditText).
inline fun startActionModeGuarded(block: () -> ActionMode?): ActionMode? {
    return try {
        block()
    } catch (e: ArithmeticException) {
        Timber.w(e, "Suppressed selection ActionMode crash (framework bug)")
        null
    }
}
