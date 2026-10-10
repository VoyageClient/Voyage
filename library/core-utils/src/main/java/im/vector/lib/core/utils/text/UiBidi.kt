/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.core.utils.text

import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import java.util.Locale

private val LRM = Char(0x200E)
private val RLM = Char(0x200F)
private val FSI = Char(0x2068)
private val PDI = Char(0x2069)

@Volatile private var cachedUiMark: Pair<Locale, Char>? = null

// The locale's name in its own script tells its direction; TextUtilsCompat does the same below API 17.
private fun uiDirectionMark(): Char {
    val locale = Locale.getDefault()
    cachedUiMark?.takeIf { it.first == locale }?.let { return it.second }
    val first = locale.getDisplayName(locale).firstOrNull()
    val rtl = first != null && Character.getDirectionality(first).let {
        it == Character.DIRECTIONALITY_RIGHT_TO_LEFT || it == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
    }
    return (if (rtl) RLM else LRM).also { cachedUiMark = locale to it }
}

/**
 * Wraps user text embedded in a UI-language sentence so its own direction can't reorder the sentence.
 * Not BidiFormatter.unicodeWrap: [neutralizeDirectionOverrides] boxes its embedding chars, and a leading
 * RLE counts as strong RTL for the paragraph.
 */
fun CharSequence.bidiIsolate(): CharSequence {
    if (isEmpty()) return this
    // Isolates are only understood (and invisible) from Marshmallow; older text stacks get UI-direction marks,
    // which keep the sentence in order but let neutrals at the edges of the user text drift.
    val open: Char
    val close: Char
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        open = FSI
        close = PDI
    } else {
        open = uiDirectionMark()
        close = open
    }
    if (this !is Spanned) return "$open$this$close"
    return SpannableStringBuilder().append(open).append(this).append(close)
}

fun String.bidiIsolate(): String = (this as CharSequence).bidiIsolate().toString()

/**
 * Pins a UI-language sentence to the UI's direction. TextViews take the paragraph direction from the first
 * strong character, so a sentence opening with an RTL name would otherwise be laid out RTL in an LTR UI.
 */
fun CharSequence.withUiDirection(): CharSequence {
    if (isEmpty() || this[0] == LRM || this[0] == RLM) return this
    val mark = uiDirectionMark()
    if (this !is Spanned) return "$mark$this"
    return SpannableStringBuilder().append(mark).append(this)
}

fun String.withUiDirection(): String = (this as CharSequence).withUiDirection().toString()

/** Format arguments for a UI sentence: text arguments are bidi-isolated, everything else passes through. */
fun Array<out Any?>.bidiIsolated(): Array<Any?> = Array(size) { i ->
    when (val arg = this[i]) {
        is CharSequence -> arg.toString().bidiIsolate()
        else -> arg
    }
}
