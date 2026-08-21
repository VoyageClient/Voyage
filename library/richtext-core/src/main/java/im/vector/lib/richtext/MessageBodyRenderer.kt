/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.richtext

import im.vector.lib.richtext.linkify.Linkifier
import im.vector.lib.richtext.linkify.MatrixIdentifiers

sealed class RenderedSegment {
    data class Text(val html: String, val text: RichText) : RenderedSegment()
    data class Code(val code: String) : RenderedSegment()
    data class Table(val rows: List<Row>) : RenderedSegment() {
        data class Row(val isHeader: Boolean, val cells: List<Cell>)
        data class Cell(val isHeader: Boolean, val alignment: Alignment, val html: String, val text: RichText)
    }
}

/** What the timeline shows for a formatted body: [text], or [segments] when a top-level table / code block is present. */
data class FormattedBody(val compressed: String, val text: RichText, val segments: List<RenderedSegment>?)

/**
 * The complete Android message-body pipeline (`MessageItemFactory.buildFormattedTextItem` + the
 * `MessageTextItem` bind): strip `<mx-reply>`, compress, render with mention pills, trim, pill plain-text
 * permalinks and `@room`, linkify, then the bind-time fixes. Not thread-safe (see [RichTextRenderer]).
 */
class MessageBodyRenderer(
        private val pillResolver: PillResolver,
        matrixIdentifiers: MatrixIdentifiers,
        latexEnabled: Boolean = true,
) {
    private val compressor = MatrixHtmlCompressor()
    private val renderer = RichTextRenderer(latexEnabled)
    private val pills = PillsPostProcessor(pillResolver)
    private val permalinkPills = PermalinkPills(pillResolver)
    private val linkifier = Linkifier(matrixIdentifiers)

    fun render(formattedBody: String): FormattedBody {
        val compressed = compressor.compress(stripExistingMxReply(formattedBody))
        val rendered = RichTextRenderer.trimUncoveredWhitespace(renderer.render(compressed, pills))
        val buffer = rendered.toSpanBuffer()
        permalinkPills.apply(buffer)
        linkifier.linkify(buffer)
        val text = bind(buffer.toRichText())
        val segments = if (compressed.contains("<table", ignoreCase = true) || compressed.contains("<pre", ignoreCase = true)) {
            HtmlBodySegmenter.segment(compressed).takeIf { segs -> segs.any { it !is BodySegment.Html } }?.map { renderSegment(it) }
        } else {
            null
        }
        return FormattedBody(compressed, text, segments)
    }

    /**
     * The single-line room-list / reply preview (`DisplayableEventFormatter`): render, drop block-level
     * spans (blockquote, code block, `<p>` padding — they'd break onto their own line), colour bare
     * links, then flatten newline runs and trim. Inline formatting, pills and emotes are kept.
     */
    fun previewBody(formattedBody: String?, plainBody: String): RichText {
        val rendered = if (formattedBody != null) {
            val buffer = renderer.render(formattedBody, pills).toSpanBuffer()
            permalinkPills.apply(buffer)
            buffer.dropBlockSpans()
            buffer.toRichText()
        } else {
            val buffer = SpanBuffer(plainBody)
            permalinkPills.apply(buffer)
            buffer.toRichText()
        }
        return rendered.flattenForPreview().trimForPreview()
    }

    /** A plain `body` (no usable `formatted_body`): permalink / `@room` pills and link detection only. */
    fun renderPlain(body: String): RichText {
        val buffer = SpanBuffer(body)
        permalinkPills.apply(buffer)
        linkifier.linkify(buffer)
        return bind(buffer.toRichText())
    }

    /** A body fragment (segment html or table cell), rendered the way `RichMessageBodyRenderer` does. */
    fun renderFragment(html: String): RichText {
        val buffer = renderer.render(html, pills).toSpanBuffer()
        linkifier.linkify(buffer)
        return bind(buffer.toRichText())
    }

    private fun renderSegment(segment: BodySegment): RenderedSegment = when (segment) {
        is BodySegment.Html -> RenderedSegment.Text(segment.html, renderFragment(segment.html))
        is BodySegment.Code -> RenderedSegment.Code(segment.code)
        is BodySegment.Table -> RenderedSegment.Table(
                segment.rows.map { row ->
                    RenderedSegment.Table.Row(
                            row.isHeader,
                            row.cells.map { cell ->
                                val cellHtml = cell.html.trim()
                                RenderedSegment.Table.Cell(
                                        cell.isHeader,
                                        cell.alignment,
                                        cell.html,
                                        if (cellHtml.isEmpty()) RichText.EMPTY else renderFragment(cellHtml),
                                )
                            }
                    )
                }
        )
    }

    private fun bind(text: RichText): RichText =
            RichTextRenderer.dropIntermediateSpans(RichTextRenderer.removeLeadingNewlineForInlineElement(text))

    companion object {
        private const val MX_REPLY_OPEN = "<mx-reply>"
        private const val MX_REPLY_CLOSE = "</mx-reply>"

        /** Drops the first legacy `<mx-reply>` block; the replied-to preview is rendered separately. */
        fun stripExistingMxReply(body: String): String {
            val start = body.indexOf(MX_REPLY_OPEN, ignoreCase = true)
            if (start == -1) return body
            val end = body.indexOf(MX_REPLY_CLOSE, startIndex = start, ignoreCase = true)
            if (end == -1) return body
            return body.substring(0, start) + body.substring(end + MX_REPLY_CLOSE.length)
        }
    }
}

// Block spans don't render on a one preview line: a blockquote draws a stripe, a code block a full-width
// bar, `<p>` padding a blank line above/below. Drop them; keep inline content (pills, emotes, formatting).
private fun SpanBuffer.dropBlockSpans() {
    allSpans().filter {
        when (val style = it.style) {
            RichStyle.Blockquote, is RichStyle.VerticalPadding, is RichStyle.LeadingMargin -> true
            is RichStyle.Code -> style.isBlock
            else -> false
        }
    }.forEach { removeSpan(it) }
}

// Collapse any run of whitespace containing a newline to a single space (dropped at the edges), so a
// block element can't break below the "Name:" line. Ported from DisplayableEventFormatter.flattenForPreview.
private fun RichText.flattenForPreview(): RichText {
    if (text.indexOf('\n') < 0 && text.indexOf('\r') < 0) return this
    val buffer = toSpanBuffer()
    fun Char.isFlattenable() = this == '\n' || this == '\r' || this == ' ' || this == '\t'
    var i = 0
    while (i < buffer.length) {
        if (buffer[i] == '\n' || buffer[i] == '\r') {
            var j = i + 1
            while (j < buffer.length && buffer[j].isFlattenable()) j++
            val replacement = if (i == 0 || j >= buffer.length) "" else " "
            buffer.replace(i, j, replacement)
            i += replacement.length
        } else {
            i++
        }
    }
    return buffer.toRichText()
}

private fun RichText.trimForPreview(): RichText {
    fun Char.isTrimable() = this == '\n' || this == '\r' || this == ' ' || this == '\t'
    var start = 0
    while (start < text.length && text[start].isTrimable()) start++
    var end = text.length
    while (end > start && text[end - 1].isTrimable()) end--
    if (start == 0 && end == text.length) return this
    val buffer = toSpanBuffer()
    buffer.delete(end, buffer.length)
    buffer.delete(0, start)
    return buffer.toRichText()
}
