/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan

const val BULLET_MARKER = "●"
private const val BULLET_MARKER_SCALE = 0.8f

// The small • glyph reads undersized next to the old drawn bullet; a scaled ● matches it
fun Spannable.scaleBulletMarker(start: Int) {
    setSpan(RelativeSizeSpan(BULLET_MARKER_SCALE), start, start + BULLET_MARKER.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

/** Draws the "• " line prefixes of a plain string resource with the timeline's list marker. */
fun CharSequence.withTimelineBullets(): CharSequence {
    val out = SpannableStringBuilder(this)
    var lineStart = 0
    while (lineStart < out.length) {
        if (out.startsWith("• ", lineStart)) {
            out.replace(lineStart, lineStart + 2, "$BULLET_MARKER ")
            out.scaleBulletMarker(lineStart)
        }
        val nl = out.indexOf('\n', lineStart)
        if (nl == -1) break
        lineStart = nl + 1
    }
    return out
}
