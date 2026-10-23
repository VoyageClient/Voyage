/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import android.text.InputFilter
import android.text.Spannable
import android.text.Spanned
import android.text.style.CharacterStyle
import android.text.style.ParagraphStyle
import android.widget.EditText

/**
 * Keeps credentials plain and on one line: formatting spans from a rich paste are dropped, surrounding line
 * breaks are trimmed, and a paste that still spans several lines is rejected.
 */
class PlainSingleLineInputFilter : InputFilter {

    override fun filter(source: CharSequence, start: Int, end: Int, dest: Spanned, dstart: Int, dend: Int): CharSequence? {
        val inserted = source.subSequence(start, end)
        if (inserted.any { it in LINE_BREAKS }) {
            val trimmed = inserted.trim { it in LINE_BREAKS }
            return if (trimmed.any { it in LINE_BREAKS }) "" else trimmed.toString()
        }
        if (source !is Spanned) return null
        val styles = source.getSpans(start, end, CharacterStyle::class.java).toList<Any>() +
                source.getSpans(start, end, ParagraphStyle::class.java)
        if (styles.isEmpty()) return null
        // Stripped in place rather than copied, as a copy would drop the IME's composing span and break typing.
        if (source is Spannable) {
            styles.forEach { source.removeSpan(it) }
            return null
        }
        return inserted.toString()
    }

    companion object {
        private const val LINE_BREAKS = "\n\r\u000B\u000C\u0085  "
    }
}

fun EditText.acceptPlainSingleLineOnly() {
    filters = filters + PlainSingleLineInputFilter()
}
