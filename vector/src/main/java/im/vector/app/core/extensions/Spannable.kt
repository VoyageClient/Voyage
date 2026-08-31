/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import android.text.Spannable
import android.text.style.AlignmentSpan
import android.text.style.LeadingMarginSpan
import android.text.style.TabStopSpan

/**
 * Drop the paragraph spans a rich-text paste carries in (blockquote, list item, alignment): they shift the
 * drawn line sideways while every offset, and the caret, stays where the unchanged characters put it.
 */
fun Spannable.removeParagraphLayoutSpans(): Boolean {
    var removed = false
    for (span in getSpans(0, length, Any::class.java)) {
        if (span is LeadingMarginSpan || span is AlignmentSpan || span is TabStopSpan) {
            removeSpan(span)
            removed = true
        }
    }
    return removed
}
