/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.richtext

/**
 * Text + spans with the exact edit semantics of Android's `SpannableStringBuilder` for
 * `SPAN_EXCLUSIVE_EXCLUSIVE` spans (the only flag Markwon uses), so the post-render passes ported
 * from the Android renderer move spans identically. Span order is insertion order.
 */
class SpanBuffer(text: CharSequence = "") : Appendable, CharSequence {

    class Span(var start: Int, var end: Int, val style: RichStyle)

    private val builder = StringBuilder(text)
    private val spans = ArrayList<Span>()

    override val length: Int get() = builder.length
    override fun get(index: Int): Char = builder[index]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = builder.subSequence(startIndex, endIndex)
    override fun toString(): String = builder.toString()

    override fun append(csq: CharSequence?): SpanBuffer = apply { builder.append(csq) }
    override fun append(csq: CharSequence?, start: Int, end: Int): SpanBuffer = apply { builder.append(csq, start, end) }
    override fun append(c: Char): SpanBuffer = apply { builder.append(c) }

    fun lastChar(): Char = builder[length - 1]

    fun indexOf(c: Char, from: Int = 0): Int = builder.indexOf(c.toString(), from)

    /** Zero-length exclusive spans are silently dropped, like the platform builder does. */
    fun setSpan(style: RichStyle, start: Int, end: Int): Span? {
        require(start in 0..end && end <= length) { "setSpan $start..$end on length $length" }
        if (start == end) return null
        return Span(start, end, style).also { spans.add(it) }
    }

    fun removeSpan(span: Span) {
        spans.remove(span)
    }

    fun allSpans(): List<Span> = spans.toList()

    inline fun <reified T : RichStyle> spansOf(): List<Span> = allSpans().filter { it.style is T }

    /** `Spanned.getSpans(start, end, Object)` overlap rule. */
    fun getSpans(start: Int, end: Int): List<Span> = spans.filter { span ->
        if (span.start > end || span.end < start) return@filter false
        if (start != end && span.start != span.end && (span.start == end || span.end == start)) return@filter false
        true
    }

    fun insert(where: Int, text: CharSequence) = replace(where, where, text)

    fun delete(start: Int, end: Int) = replace(start, end, "")

    fun replace(start: Int, end: Int, text: CharSequence) {
        require(start in 0..end && end <= length) { "replace $start..$end on length $length" }
        val newLen = text.length
        val textIsRemoved = newLen == 0 && end > start
        val delta = newLen - (end - start)
        val iterator = spans.iterator()
        while (iterator.hasNext()) {
            val span = iterator.next()
            val inside = span.start >= start && span.end <= end
            if (inside && (textIsRemoved || span.start > start || span.end < end)) {
                iterator.remove()
                continue
            }
            val s = span.start
            span.start = when {
                s < start -> s
                s < end -> if (textIsRemoved || s > start) start + newLen else start
                else -> s + delta
            }
            val e = span.end
            span.end = when {
                e < start -> e
                e < end -> start
                e == end -> if (start == end) start else start + newLen
                else -> e + delta
            }
            if (span.start >= span.end) iterator.remove()
        }
        builder.replace(start, end, text.toString())
    }

    fun toRichText(): RichText = RichText(
            builder.toString(),
            spans.map { RichSpan(it.start, it.end, it.style) }
    )
}

/** Spans as Markwon's `SpannableBuilder.getSpans` reports them: overlap rule and newest first. */
fun SpanBuffer.markwonGetSpans(start: Int, end: Int): List<SpanBuffer.Span> {
    val length = length
    if (!(end > start && start >= 0 && end <= length)) return emptyList()
    val all = allSpans().asReversed()
    if (start == 0 && end == length) return all
    return all.filter { span ->
        (span.start >= start && span.start < end) ||
                (span.end <= end && span.end > start) ||
                (span.start < start && span.end > end)
    }
}

class DetachedText(val text: String, val spans: List<RichSpan>)

/** Markwon's `SpannableBuilder.removeFromEnd`: cuts the tail off, carrying the spans fully inside it. */
fun SpanBuffer.removeFromEnd(start: Int): DetachedText {
    val end = length
    val carried = ArrayList<RichSpan>()
    for (span in allSpans().asReversed()) {
        if (span.start >= start && span.end <= end) {
            carried.add(RichSpan(span.start - start, span.end - start, span.style))
            removeSpan(span)
        }
    }
    val text = subSequence(start, end).toString()
    delete(start, end)
    return DetachedText(text, carried)
}

fun SpanBuffer.appendWithSpans(detached: DetachedText) {
    val offset = length
    append(detached.text)
    detached.spans.forEach { setSpan(it.style, offset + it.start, offset + it.end) }
}
