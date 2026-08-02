/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.render

import android.content.Context
import android.os.Build
import android.view.ActionMode
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.AppCompatTextView
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import im.vector.app.core.utils.CodeSelectionBoundsHost
import im.vector.app.core.utils.TableSourceProvider
import im.vector.app.core.utils.buildTableMarkdown
import im.vector.app.core.utils.clampSelectionToCodeSpans
import im.vector.app.core.utils.mirrorPressedToRowFlash
import im.vector.app.core.utils.readOnlySelectionInputConnection
import im.vector.app.core.utils.releasePressedRippleOnSelection
import im.vector.app.core.utils.replaySwallowedTap
import im.vector.app.core.utils.setReadOnlySelectable
import im.vector.app.core.utils.startActionModeGuarded

/**
 * TextView for rich-body segments that may offer read-only selection: [selectable] enables it
 * up front (code blocks), or callers enable it later once the rendered spans are known.
 */
class ReadOnlySelectableTextView(context: Context, selectable: Boolean = false) :
        AppCompatTextView(context), CodeSelectionBoundsHost, TableSourceProvider {

    init {
        if (selectable) setReadOnlySelectable(true)
    }

    override var codeSelectionBounds: IntRange? = null

    // When this view is a table cell, the whole table's cells, so the selection menu can offer
    // "Copy table". Selection itself never leaves the cell.
    var tableCellRows: List<List<AppCompatTextView>>? = null

    override fun tableMarkdownSource(): String? = tableCellRows?.let { buildTableMarkdown(it) }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        codeSelectionBounds = clampSelectionToCodeSpans(codeSelectionBounds)
        releasePressedRippleOnSelection(selStart, selEnd)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        mirrorPressedToRowFlash(pressed)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val wasFocused = isFocused
        val handled = super.onTouchEvent(event)
        replaySwallowedTap(event, wasFocused)
        return handled
    }

    // Touch-only focus: focus search (e.g. HorizontalScrollView.fling grabbing the nearest
    // focusable) landing on a selectable view churns selection spans, and every span change
    // relayouts the whole row — a remeasure storm on tables
    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        if (!isTextSelectable) super.addFocusables(views, direction, focusableMode)
    }

    override fun onCheckIsTextEditor(): Boolean = isTextSelectable

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
            if (isTextSelectable) readOnlySelectionInputConnection(outAttrs) else super.onCreateInputConnection(outAttrs)

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
            startActionModeGuarded { super.startActionMode(callback) }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
            startActionModeGuarded { super.startActionMode(callback, type) }
}
