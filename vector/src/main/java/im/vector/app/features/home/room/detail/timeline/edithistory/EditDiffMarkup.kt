/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.edithistory

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import androidx.annotation.ColorInt
import im.vector.app.features.html.PILL_PLACEHOLDER
import im.vector.app.features.html.PillImageSpan
import name.fraser.neil.plaintext.diff_match_patch

/**
 * A revision's rendered text with the previous version's differences marked on it: what is gone struck
 * out in [removedColor], what arrived in [addedColor]. Built over what the timeline rendered rather than
 * over the raw body, so everything it put there survives being diffed.
 */
object EditDiffMarkup {
    private const val SPAN_FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    private const val PILL_KEY_BASE = 0xE000.toChar()

    fun markUpChanges(previous: CharSequence, current: CharSequence, @ColorInt removedColor: Int, @ColorInt addedColor: Int): CharSequence {
        val pillKeys = mutableMapOf<String, Char>()
        val dmp = diff_match_patch()
        val diff = dmp.diff_main(previous.diffKey(pillKeys), current.diffKey(pillKeys))
        dmp.diff_cleanupSemantic(diff)
        val marked = SpannableStringBuilder()
        var at = 0
        var previousAt = 0
        diff.forEach { change ->
            when (change.operation) {
                diff_match_patch.Operation.DELETE -> {
                    // Gone from this version: spelled out as plain text, keeping only its pills.
                    val from = marked.length
                    marked.append(previous.subSequence(previousAt, previousAt + change.text.length).toString().replace('\n', ' '))
                    (previous as? Spanned)?.getSpans(previousAt, previousAt + change.text.length, PillImageSpan::class.java)?.forEach { pill ->
                        val start = maxOf(previous.getSpanStart(pill), previousAt) - previousAt + from
                        val end = minOf(previous.getSpanEnd(pill), previousAt + change.text.length) - previousAt + from
                        if (start < end) marked.setSpan(pill, start, end, SPAN_FLAGS)
                    }
                    marked.setSpan(ForegroundColorSpan(removedColor), from, marked.length, SPAN_FLAGS)
                    marked.setSpan(StrikethroughSpan(), from, marked.length, SPAN_FLAGS)
                    previousAt += change.text.length
                }
                diff_match_patch.Operation.INSERT -> {
                    val from = marked.length
                    marked.append(current.subSequence(at, at + change.text.length))
                    marked.setSpan(ForegroundColorSpan(addedColor), from, marked.length, SPAN_FLAGS)
                    at += change.text.length
                }
                else -> {
                    marked.append(current.subSequence(at, at + change.text.length))
                    at += change.text.length
                    previousAt += change.text.length
                }
            }
        }
        return marked
    }

    // Every pill is the same placeholder char, so give each target its own private-use char; otherwise
    // swapping one mention for another would diff as unchanged.
    private fun CharSequence.diffKey(pillKeys: MutableMap<String, Char>): String {
        val key = StringBuilder(this)
        (this as? Spanned)?.getSpans(0, length, PillImageSpan::class.java)?.forEach { pill ->
            val start = getSpanStart(pill)
            if (start >= 0 && start < length && this[start] == PILL_PLACEHOLDER[0]) {
                key.setCharAt(start, pillKeys.getOrPut(pill.matrixItem.id) { PILL_KEY_BASE + pillKeys.size })
            }
        }
        return key.toString()
    }
}
