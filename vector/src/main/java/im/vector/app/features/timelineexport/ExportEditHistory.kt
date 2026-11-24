/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ClickableSpan
import android.view.View

/** An exported message's "(edited)" marker opens its revisions, as the timeline's does. */
object ExportEditHistory {
    fun hasHistory(options: TimelineExportOptions, event: ExportEvent) = options.editHistory && event.history.isNotEmpty()

    /** Newest first, each as a message of its own. */
    fun revisions(event: ExportEvent): List<ExportEvent> = event.history.mapIndexed { index, revision ->
        event.copy(id = event.id + "-revision-" + index, timestamp = revision.timestamp,
                body = revision.body, html = revision.html, raw = revision.raw, edited = false, history = emptyList(),
                receipts = emptyList(), reactions = emptyList(), attachment = null, extraAttachments = emptyList(),
                replyTo = null, replyMissing = false, threadCount = 0)
    }.asReversed()

    fun revisionOptions(options: TimelineExportOptions) = options.copy(roomHeader = false, dates = false, timestamps = false)

    // MessageSuffixFormatter sizes the marker down with its own span, which tells it apart from body text.
    fun suffixRange(text: Spanned, suffix: String): Pair<Int, Int>? = text.getSpans(0, text.length, AbsoluteSizeSpan::class.java)
            .map { text.getSpanStart(it) to text.getSpanEnd(it) }
            .firstOrNull { (start, end) -> start >= 0 && text.subSequence(start, end).toString() == suffix }

    fun withSuffixClick(text: CharSequence, suffix: String, onClick: () -> Unit): CharSequence {
        val range = (text as? Spanned)?.let { suffixRange(it, suffix) } ?: return text
        return SpannableStringBuilder(text).apply {
            setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = onClick()
                override fun updateDrawState(ds: TextPaint) = Unit
            }, range.first, range.second, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
