/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.text.Spannable
import android.text.Spanned
import android.widget.TextView

/**
 * Repaint the area a span draws.
 *
 * A bare [TextView.invalidate] is not enough: the TextView re-records its display list from the
 * cached text Layout, which (observed on Android 14+) can skip re-running the span's draw — an
 * async image or an animation frame then never reaches the screen. Re-setting the span notifies the
 * Layout to reflow just that range, which does.
 *
 * Sizes must be reserved up front, so the reflow causes no visible movement.
 */
fun TextView.repaintSpan(span: Any) {
    val current = text
    val start = (current as? Spanned)?.getSpanStart(span) ?: -1
    when {
        // Reflows just this range. Cheaper than re-setting the text, and it leaves the editor alone — on a
        // selectable view (a topic, a biography, a message) setting the text drops the selection the user
        // is making and can raise the soft keyboard.
        start >= 0 && current is Spannable -> current.setSpan(span, start, current.getSpanEnd(span), current.getSpanFlags(span))
        // An immutable buffer has no such notification; only setting the text rebuilds the Layout.
        start >= 0 -> text = current
        // Not in this view's text any more (a recycled row): nothing to reflow.
        else -> invalidate()
    }
}
