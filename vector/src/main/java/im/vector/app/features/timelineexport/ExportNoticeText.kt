/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import im.vector.app.features.home.room.detail.timeline.tools.SenderNameSpan
import im.vector.app.features.html.EmoteImageSpan
import im.vector.app.features.html.expandPillSpans
import org.matrix.android.sdk.api.session.room.send.MatrixEmoteSpan

suspend fun exportNoticeText(text: CharSequence, imageLabel: String, asset: suspend (String) -> String?): Pair<String, List<ExportNoticeSpan>> {
    val result = SpannableStringBuilder(text.expandPillSpans())
    val images = mutableListOf<ExportNoticeSpan>()
    val markers = mutableListOf<Any>()
    result.getSpans(0, result.length, MatrixEmoteSpan::class.java).sortedByDescending { result.getSpanStart(it) }.forEach { span ->
        val start = result.getSpanStart(span)
        val end = result.getSpanEnd(span)
        if (start < 0 || end <= start) return@forEach
        val label = emoteLabel(span, imageLabel)
        val path = asset(span.mxcUrl)
        result.removeSpan(span)
        result.replace(start, end, label)
        if (path != null) {
            val marker = Any()
            markers.add(marker)
            images.add(ExportNoticeSpan(0, 0, image = path))
            result.setSpan(marker, start, start + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    val spans = images.mapIndexed { index, image -> image.copy(start = result.getSpanStart(markers[index]), end = result.getSpanEnd(markers[index])) } +
            result.getSpans(0, result.length, SenderNameSpan::class.java).map { span ->
                ExportNoticeSpan(result.getSpanStart(span), result.getSpanEnd(span), person = span.userId)
            }
    return result.toString() to spans
}

private fun emoteLabel(span: MatrixEmoteSpan, imageLabel: String): String =
        span.body?.takeIf { it.isNotBlank() && '\uFFFC' !in it } ?: span.shortcode.takeIf { it.isNotBlank() && '\uFFFC' !in it } ?: imageLabel

fun CharSequence.exportPlainText(imageLabel: String): String {
    val result = SpannableStringBuilder(expandPillSpans())
    result.getSpans(0, result.length, MatrixEmoteSpan::class.java).sortedByDescending { result.getSpanStart(it) }.forEach { span ->
        val start = result.getSpanStart(span)
        val end = result.getSpanEnd(span)
        if (start >= 0 && end > start) { result.removeSpan(span); result.replace(start, end, emoteLabel(span, imageLabel)) }
    }
    return result.toString()
}

fun ExportEvent.noticeText(people: Map<String, ExportPerson>, coloredNames: Boolean, bitmap: (String) -> Bitmap?): CharSequence = SpannableStringBuilder(body).apply {
    noticeSpans.filter { it.start >= 0 && it.end > it.start && it.end <= length }.forEach { span ->
        span.image?.let { path -> setSpan(EmoteImageSpan("", path, body.substring(span.start, span.end), null) { bitmap(path) }, span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        span.person?.let(people::get)?.let { person ->
            setSpan(ForegroundColorSpan(person.color), span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (coloredNames) setSpan(StyleSpan(Typeface.BOLD), span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
