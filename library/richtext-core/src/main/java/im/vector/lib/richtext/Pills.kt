/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.richtext

const val PILL_PLACEHOLDER = "￼"

enum class PillKind { USER, ROOM, ROOM_ALIAS, SPACE, EVERYONE }

/** Decides what becomes a pill; the client supplies permalink parsing, profile lookups and strings. */
interface PillResolver {
    /** A `<a href>` mention: user / room / alias permalinks only (event links are left as links). */
    fun resolveLink(url: String): PillTarget?

    /** A permalink found in the text body, event links included (their display text is client-provided). */
    fun resolvePermalink(url: String): PillTarget?

    /** The `@room` pill, or null when there is no room context. */
    fun notifyEveryone(): PillTarget?
}

class PillsPostProcessor(private val resolver: PillResolver) : RichTextRenderer.PostProcessor {

    override fun afterRender(text: SpanBuffer) {
        val codeSpans = text.spansOf<RichStyle.Code>()
        val linkSpans = text.spansOf<RichStyle.Link>()
        for (linkSpan in linkSpans) {
            val startSpan = linkSpan.start
            val endSpan = linkSpan.end
            // A mention/permalink inside inline code or a code block stays verbatim.
            if (codeSpans.any { it.start < endSpan && startSpan < it.end }) continue
            val target = resolver.resolveLink((linkSpan.style as RichStyle.Link).url) ?: continue
            // Spans nested inside the link would draw on top of the pill; drop everything the link contains.
            text.getSpans(startSpan, endSpan).forEach {
                if (it.style !is RichStyle.Link && it.start >= startSpan && it.end <= endSpan) text.removeSpan(it)
            }
            text.setPillSpan(RichStyle.Pill(target), startSpan, endSpan)
        }
    }
}

/** Collapses the backing text to one object-replacement char (a display name with emoji would get split by layout). */
fun SpanBuffer.setPillSpan(pill: RichStyle.Pill, start: Int, end: Int) {
    if (end > start) {
        replace(start, end, PILL_PLACEHOLDER)
        setSpan(pill, start, start + PILL_PLACEHOLDER.length)
    } else {
        setSpan(pill, start, end)
    }
}
