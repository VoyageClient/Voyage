/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import im.vector.app.features.html.HtmlCodeSpan
import im.vector.app.features.html.ListMarkerSpan
import im.vector.app.features.html.PillImageSpan
import im.vector.app.features.html.QuoteMarginSpan
import io.noties.markwon.core.spans.EmphasisSpan
import io.noties.markwon.core.spans.HeadingSpan
import io.noties.markwon.core.spans.StrongEmphasisSpan
import io.noties.markwon.core.spans.ThematicBreakSpan
import io.noties.markwon.html.span.SubScriptSpan
import io.noties.markwon.html.span.SuperScriptSpan
import me.gujun.android.span.style.CustomTypefaceSpan
import org.matrix.android.sdk.api.session.permalinks.PermalinkService
import org.matrix.android.sdk.api.util.MatrixItem

/**
 * Rebuilds markdown source for a slice of rendered message text. Visible markers that are real
 * characters (materialized list markers) are swapped for their source only when actually
 * selected; invisible inline syntax (styling, links, pills) is implied whenever the text it
 * produces is selected. Line prefixes (quotes, headings) are anchored at the line start, so they
 * are implied only for lines whose start is inside the selection — grabbing a word from the
 * middle of a quoted line copies just the word.
 */
fun Spanned.toMarkdownSource(selStart: Int, selEnd: Int): String {
    val start = minOf(selStart, selEnd).coerceIn(0, length)
    val end = maxOf(selStart, selEnd).coerceIn(0, length)
    if (start >= end) return ""

    // phase orders insertions sharing a position: close previous spans, then line prefix, then openers
    class Insertion(val pos: Int, val phase: Int, val order: Int, val text: String)
    class Replacement(val start: Int, val end: Int, val text: String)

    val insertions = ArrayList<Insertion>()

    fun addInline(spanStart: Int, spanEnd: Int, open: String, close: String) {
        val s = maxOf(spanStart, start)
        val e = minOf(spanEnd, end)
        if (s >= e) return
        insertions.add(Insertion(s, 2, -spanEnd, open))
        insertions.add(Insertion(e, 0, -spanStart, close))
    }

    val replacements = ArrayList<Replacement>()
    getSpans(start, end, PillImageSpan::class.java)
            .filterTo(ArrayList()) { getSpanStart(it) >= start && getSpanEnd(it) <= end }
            .mapTo(replacements) { Replacement(getSpanStart(it), getSpanEnd(it), pillSource(it.matrixItem)) }
    // Markers partially in the selection still emit their whole source; not at all → nothing
    getSpans(start, end, ListMarkerSpan::class.java).mapTo(replacements) {
        Replacement(maxOf(getSpanStart(it), start), minOf(getSpanEnd(it), end), it.source)
    }
    getSpans(start, end, ThematicBreakSpan::class.java).mapTo(replacements) {
        Replacement(maxOf(getSpanStart(it), start), minOf(getSpanEnd(it), end), "---")
    }
    replacements.sortBy { it.start }

    for (span in getSpans(start, end, Any::class.java)) {
        val s = getSpanStart(span)
        val e = getSpanEnd(span)
        when {
            span is URLSpan -> {
                // A pill already replaces its matrix.to link; bare autolinked URLs are their own source
                if (replacements.none { it.start <= s && e <= it.end }) {
                    val url = span.url.orEmpty()
                    if (url.isNotEmpty() && subSequence(s, e).toString() != url) {
                        addInline(s, e, "[", "]($url)")
                    }
                }
            }
            span is StrongEmphasisSpan -> addInline(s, e, "**", "**")
            span is EmphasisSpan || span is CustomTypefaceSpan -> addInline(s, e, "*", "*")
            span is UnderlineSpan -> addInline(s, e, "__", "__")
            span is StrikethroughSpan -> addInline(s, e, "~~", "~~")
            span is SubScriptSpan -> addInline(s, e, "~", "~")
            span is SuperScriptSpan -> addInline(s, e, "^", "^")
            span is HtmlCodeSpan -> if (span.isBlock) addInline(s, e, "```\n", "\n```") else addInline(s, e, "`", "`")
        }
    }

    val str = toString()
    var paragraph = start
    while (paragraph < end) {
        val lineEnd = str.indexOf('\n', paragraph).let { if (it == -1 || it > end) end else it }
        if (paragraph > 0 && str[paragraph - 1] != '\n') {
            paragraph = lineEnd + 1
            continue
        }
        val probe = minOf(paragraph + 1, end)
        fun <T> covering(clazz: Class<T>) = getSpans(paragraph, probe, clazz)
                .filter { getSpanStart(it) <= paragraph && getSpanEnd(it) > paragraph }
        val prefix = StringBuilder()
        repeat(covering(QuoteMarginSpan::class.java).size) { prefix.append("> ") }
        covering(HeadingSpan::class.java).maxOfOrNull { it.level }?.let {
            prefix.append("#".repeat(it)).append(' ')
        }
        if (prefix.isNotEmpty()) insertions.add(Insertion(paragraph, 1, 0, prefix.toString()))
        paragraph = lineEnd + 1
    }

    insertions.sortWith(compareBy({ it.pos }, { it.phase }, { it.order }))
    val sb = StringBuilder()
    var idx = 0
    var repIdx = 0
    var i = start
    while (true) {
        while (idx < insertions.size && insertions[idx].pos <= i) {
            sb.append(insertions[idx].text)
            idx++
        }
        if (i >= end) break
        while (repIdx < replacements.size && replacements[repIdx].start < i) repIdx++
        val rep = replacements.getOrNull(repIdx)?.takeIf { it.start == i }
        if (rep != null) {
            sb.append(rep.text)
            i = maxOf(rep.end, i + 1)
            repIdx++
        } else {
            sb.append(str[i])
            i++
        }
    }
    return sb.toString()
}

private fun pillSource(item: MatrixItem): String {
    val id = item.id
    val name = item.displayName?.takeIf { it.isNotBlank() } ?: id
    return if (id.startsWith("@") || id.startsWith("#") || id.startsWith("!")) {
        "[$name](${PermalinkService.MATRIX_TO_URL_BASE}$id)"
    } else {
        name
    }
}
