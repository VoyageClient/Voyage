/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.epoxy

import android.annotation.SuppressLint
import android.graphics.PointF
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import im.vector.app.core.utils.DebouncedClickListener
import im.vector.app.features.html.HtmlCodeSpan

/**
 * View.OnClickListener lambda.
 */
typealias ClickListener = (View) -> Unit

fun View.onClick(listener: ClickListener?) {
    if (listener == null) {
        setOnClickListener(null)
    } else {
        setOnClickListener(DebouncedClickListener(listener))
    }
}

fun TextView.onLongClickIgnoringLinks(listener: View.OnLongClickListener?) {
    if (listener == null) {
        setOnLongClickListener(null)
    } else {
        setOnLongClickListener(object : View.OnLongClickListener {
            override fun onLongClick(v: View): Boolean {
                if (hasLongPressedLink()) {
                    return false
                }
                return listener.onLongClick(v)
            }

            /**
             * Infer that a Clickable span has been click by the presence of a selection.
             */
            private fun hasLongPressedLink() = selectionStart != -1 || selectionEnd != -1
        })
    }
}

/**
 * Long-click for message text views whose code spans are selectable: a press on a code span
 * starts text selection, a press on a link stays with the movement method's own long-click
 * handling, anything else goes to [listener] (the message action menu). On a non-selectable
 * view this degrades to [onLongClickIgnoringLinks] behavior.
 */
@SuppressLint("ClickableViewAccessibility")
fun TextView.onLongClickIgnoringLinksSelectingCode(listener: View.OnLongClickListener?) {
    if (listener == null) {
        setOnTouchListener(null)
        setOnLongClickListener(null)
        return
    }
    val touch = PointF()
    setOnTouchListener { _, event ->
        touch.set(event.x, event.y)
        false
    }
    setOnLongClickListener(object : View.OnLongClickListener {
        override fun onLongClick(v: View): Boolean {
            if (isTextSelectable) {
                if (touchedSpan(HtmlCodeSpan::class.java) != null) {
                    // Not handled: the framework starts text selection
                    return false
                }
                if (touchedSpan(ClickableSpan::class.java) != null) {
                    // Consume so selection doesn't also start over the pressed link
                    return true
                }
            } else if (selectionStart != -1 || selectionEnd != -1) {
                // A selection on a non-selectable view can only be the movement method marking a
                // pressed link (see onLongClickIgnoringLinks)
                return false
            }
            return listener.onLongClick(v)
        }

        private fun <T> touchedSpan(clazz: Class<T>): T? {
            val spanned = text as? Spanned ?: return null
            val layout = layout ?: return null
            val x = touch.x - totalPaddingLeft + scrollX
            val y = (touch.y - totalPaddingTop + scrollY).toInt()
            if (y < 0 || y > layout.height) return null
            val line = layout.getLineForVertical(y)
            if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
            val offset = layout.getOffsetForHorizontal(line, x)
            return spanned.getSpans(offset, offset, clazz).firstOrNull()
        }
    })
}

/**
 * Simple Text listener lambda.
 */
typealias TextListener = (String) -> Unit
