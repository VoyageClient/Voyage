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
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import im.vector.app.core.utils.DebouncedClickListener
import im.vector.app.core.utils.copyToClipboard
import im.vector.app.features.html.HtmlCodeSpan
import kotlin.math.abs

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
    var tapCandidate: HtmlCodeSpan? = null
    val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    fun codeAt(x: Float, y: Float): HtmlCodeSpan? {
        val spanned = text as? Spanned ?: return null
        val textLayout = layout ?: return null
        val localX = x - totalPaddingLeft + scrollX
        val localY = (y - totalPaddingTop + scrollY).toInt()
        if (localY < 0 || localY >= textLayout.height) return null
        val line = textLayout.getLineForVertical(localY)
        if (localX < textLayout.getLineLeft(line) || localX >= textLayout.getLineRight(line)) return null
        val offset = textLayout.getOffsetForHorizontal(line, localX)
        if (spanned.getSpans(offset, offset, ClickableSpan::class.java).isNotEmpty()) return null
        return spanned.getSpans(offset, offset, HtmlCodeSpan::class.java).firstOrNull {
            !it.isBlock && offset < spanned.getSpanEnd(it)
        }
    }
    setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> tapCandidate = codeAt(event.x, event.y)
            MotionEvent.ACTION_MOVE -> if (abs(event.x - touch.x) > touchSlop ||
                    abs(event.y - touch.y) > touchSlop) tapCandidate = null
            MotionEvent.ACTION_POINTER_DOWN -> tapCandidate = null
            MotionEvent.ACTION_CANCEL -> tapCandidate = null
            MotionEvent.ACTION_UP -> {
                val code = tapCandidate
                tapCandidate = null
                if (code != null && codeAt(event.x, event.y) === code &&
                        event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout() && !hasSelection()) {
                    val spanned = text as Spanned
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    try {
                        onTouchEvent(cancel)
                    } finally {
                        cancel.recycle()
                    }
                    copyToClipboard(context, spanned.subSequence(spanned.getSpanStart(code), spanned.getSpanEnd(code)).toString())
                    isPressed = false
                    return@setOnTouchListener true
                }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) touch.set(event.x, event.y)
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
