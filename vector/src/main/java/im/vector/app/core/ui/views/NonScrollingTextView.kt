/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.ActionMode
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.annotation.RequiresApi
import com.google.android.material.textview.MaterialTextView
import im.vector.app.core.utils.ReadOnlySelectionFocus
import im.vector.app.core.utils.SelectionFocusHost
import im.vector.app.core.utils.readOnlySelectionInputConnection
import im.vector.app.core.utils.replaySwallowedTap
import im.vector.app.core.utils.startActionModeGuarded

class NonScrollingTextView : MaterialTextView, SelectionFocusHost {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun scrollTo(x: Int, y: Int) {
        // NOOP
    }

    override val selectionFocus = ReadOnlySelectionFocus(this)

    override fun setTextIsSelectable(selectable: Boolean) {
        super.setTextIsSelectable(selectable)
        if (selectable) isFocusableInTouchMode = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val wasFocused = isFocused
        selectionFocus.beforeTouch(event)
        val handled = super.onTouchEvent(event)
        replaySwallowedTap(event, wasFocused)
        selectionFocus.afterTouch(event)
        return handled
    }

    override fun performClick(): Boolean = super.performClick()

    override fun performLongClick(): Boolean {
        selectionFocus.beforeLongClick()
        return super.performLongClick()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) selectionFocus.endSelection(restorePreviousFocus = false)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        selectionFocus.detach()
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
