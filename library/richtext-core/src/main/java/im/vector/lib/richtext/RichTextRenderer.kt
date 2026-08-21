/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.richtext

import io.noties.markwon.ext.latex.JLatexMathBlock
import io.noties.markwon.ext.latex.JLatexMathNode
import io.noties.markwon.ext.latex.JLatexMathParsers
import io.noties.markwon.html.HtmlTag
import io.noties.markwon.html.MarkwonHtmlParser
import io.noties.markwon.html.MarkwonHtmlParserImpl
import io.noties.markwon.inlineparser.EntityInlineProcessor
import io.noties.markwon.inlineparser.HtmlInlineProcessor
import io.noties.markwon.inlineparser.MarkwonInlineParser
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser

/**
 * Matrix `formatted_body` → [RichText], byte-for-byte the text and span layout the Android app's
 * Markwon setup produces (see GOLDEN.md). Not thread-safe: the HTML parser keeps state per render.
 */
class RichTextRenderer(private val latexEnabled: Boolean = true) {

    fun interface PostProcessor {
        fun afterRender(text: SpanBuffer)
    }

    private val parser: Parser = Parser.builder()
            .enabledBlockTypes(emptySet())
            .inlineParserFactory(
                    MarkwonInlineParser.factoryBuilderNoDefaults()
                            .addInlineProcessor(HtmlInlineProcessor())
                            .addInlineProcessor(EntityInlineProcessor())
                            .apply { if (latexEnabled) addInlineProcessor(JLatexMathParsers.inlineProcessor()) }
                            .build()
            )
            .apply { if (latexEnabled) customBlockParserFactory(JLatexMathParsers.blockParserFactory()) }
            .build()

    private val htmlParser: MarkwonHtmlParser = MarkwonHtmlParserImpl.create()
    private val handlers = matrixTagHandlers()

    /** Renders; on any failure returns the input as plain text, as the Android renderer does. */
    fun render(html: String, vararg postProcessors: PostProcessor): RichText {
        return try {
            renderOrThrow(html, postProcessors)
        } catch (failure: Throwable) {
            htmlParser.reset()
            RichText(html, emptyList())
        }
    }

    fun renderOrThrow(html: String, postProcessors: Array<out PostProcessor> = emptyArray()): RichText {
        val document = parser.parse(processMarkdown(html))
        val builder = SpanBuffer()
        Visitor(builder).visit(document)
        val ctx = HtmlRenderContext(builder, handlers)
        htmlParser.flushInlineTags(HtmlTag.NO_END) { tags -> ctx.applyInline(tags) }
        htmlParser.flushBlockTags(HtmlTag.NO_END) { tags -> ctx.applyBlocks(tags) }
        htmlParser.reset()
        collapseBlockQuotePadding(builder)
        separateBlockQuoteTrailingContent(builder)
        collapsePhantomWhitespaceLines(builder)
        materializeListMarkers(builder)
        // A trailing <p>/<br> leaves a dangling newline/space; trailing whitespace is never significant.
        var end = builder.length
        while (end > 0 && builder[end - 1].let { it == '\n' || it == ' ' || it == '\t' }) end--
        if (end < builder.length) builder.delete(end, builder.length)
        postProcessors.forEach { it.afterRender(builder) }
        return builder.toRichText()
    }

    private fun processMarkdown(html: String): String {
        var markdown = "<$ROOT_TAG_NAME $ROOT_ATTRIBUTE>$html</$ROOT_TAG_NAME>"
        if (latexEnabled) {
            // Markwon's LaTeX support keys off `$$…$$`, so data-mx-maths is rewritten into that shape first.
            markdown = markdown
                    .replace(Regex("""<span\s[^>]*?data-mx-maths="([^"]*)"[^>]*>.*?</span>""")) { match ->
                        "$$" + match.groupValues[1].unescapeHtmlEntities() + "$$"
                    }
                    .replace(Regex("""<div\s[^>]*?data-mx-maths="([^"]*)"[^>]*>.*?</div>""")) { match ->
                        "\n$$\n" + match.groupValues[1].unescapeHtmlEntities() + "\n$$\n"
                    }
        }
        return markdown
    }

    private fun String.unescapeHtmlEntities(): String = replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")

    /** Markwon's visitor for the node types this parser configuration can produce. */
    private inner class Visitor(private val builder: SpanBuffer) {

        fun visit(node: Node) {
            when (node) {
                is Text -> builder.append(node.literal)
                is SoftLineBreak -> builder.append(' ')
                is HardLineBreak -> ensureNewLine()
                is Paragraph -> {
                    val inTightList = isInTightList(node)
                    if (!inTightList) blockStart()
                    visitChildren(node)
                    if (!inTightList) blockEnd(node)
                }
                is HtmlInline -> node.literal?.let { htmlParser.processFragment(builder, it) }
                is HtmlBlock -> node.literal?.let { htmlParser.processFragment(builder, it) }
                is JLatexMathBlock -> {
                    blockStart()
                    val length = builder.length
                    builder.append(latexPlaceholder(node.latex()))
                    setSpans(RichStyle.Maths(node.latex(), isBlock = true), length)
                    blockEnd(node)
                }
                is JLatexMathNode -> {
                    val length = builder.length
                    builder.append(latexPlaceholder(node.latex()))
                    setSpans(RichStyle.Maths(node.latex(), isBlock = false), length)
                }
                else -> visitChildren(node)
            }
        }

        private fun visitChildren(parent: Node) {
            var node = parent.firstChild
            while (node != null) {
                val next = node.next
                visit(node)
                node = next
            }
        }

        private fun setSpans(style: RichStyle, start: Int) {
            val end = builder.length
            if (end > start && start >= 0) builder.setSpan(style, start, end)
        }

        private fun blockStart() = ensureNewLine()

        private fun blockEnd(node: Node) {
            if (node.next != null) {
                ensureNewLine()
                builder.append('\n')
            }
        }

        private fun ensureNewLine() {
            if (builder.length > 0 && builder.lastChar() != '\n') builder.append('\n')
        }

        private fun isInTightList(paragraph: Paragraph): Boolean {
            val parent = paragraph.parent as? ListItem ?: return false
            val list = parent.parent as? ListBlock ?: return false
            return list.isTight
        }

        // Android draws the formula per text line, so the placeholder must stay on one line.
        private fun latexPlaceholder(latex: String): String = latex.replace('\n', ' ').trim()
    }

    // ---- Post-render passes, ported from EventHtmlRenderer ----

    // Selection can only cover real characters, so replace the margin-drawn list markers with
    // literal ones ("● " / "1. ", space-indented when nested); ListMarker carries each marker's
    // markdown source for copy.
    private fun materializeListMarkers(text: SpanBuffer) {
        val spans = text.spansOf<RichStyle.ListItem>()
        if (spans.isEmpty()) return
        val markers = spans.map { span ->
            val start = span.start
            val depth = spans.count { it !== span && it.start <= start && it.end >= span.end }
            Triple(span, start, depth)
        }.sortedWith(compareByDescending<Triple<SpanBuffer.Span, Int, Int>> { it.second }.thenByDescending { it.third })
        for ((span, start, depth) in markers) {
            if (start < 0) continue
            text.removeSpan(span)
            val number = (span.style as RichStyle.ListItem).number
            val (display, source) = if (number != null) "$number.\u00a0" to "$number. " else "●\u00a0" to "- "
            val indent = "   ".repeat(depth)
            text.insert(start, indent + display)
            if (number == null) {
                // The small • glyph reads undersized next to the old drawn bullet; a scaled ● matches it.
                text.setSpan(RichStyle.RelativeSize(0.8f), start + indent.length, start + indent.length + 1)
            }
            text.setSpan(RichStyle.ListMarker(indent + source), start, start + indent.length + display.length)
        }
    }

    // The compressor turns the newlines markdown leaves between tags into single spaces, which Markwon
    // strands on lines of their own around list boundaries. Space-only lines are always that artifact
    // (real blank lines are empty), so drop every list-related run; a run between two paragraphs of the
    // SAME item keeps its blank line like top-level paragraphs; non-list runs and code spans are untouched.
    private fun collapsePhantomWhitespaceLines(text: SpanBuffer) {
        val listRanges = text.spansOf<RichStyle.ListItem>().map { it.start to it.end }
        if (listRanges.isEmpty()) return
        val codeRanges = text.spansOf<RichStyle.Code>().filter { (it.style as RichStyle.Code).isBlock }.map { it.start to it.end }

        val runs = ArrayList<IntArray>()
        var pos = 0
        while (pos < text.length) {
            val nl = text.indexOf('\n', pos).let { if (it == -1) text.length else it }
            val whitespaceOnly = nl > pos &&
                    (pos until nl).all { text[it] == ' ' || text[it] == '\t' } &&
                    codeRanges.none { (s, e) -> s < nl && e > pos }
            if (whitespaceOnly) {
                val lineEnd = (nl + 1).coerceAtMost(text.length)
                val last = runs.lastOrNull()
                if (last != null && last[1] == pos) last[1] = lineEnd else runs.add(intArrayOf(pos, lineEnd))
            }
            pos = nl + 1
        }

        fun Char.isWs() = this == ' ' || this == '\t' || this == '\n'
        fun innermostItem(index: Int) = listRanges.filter { (s, e) -> index in s until e }.minByOrNull { (s, e) -> e - s }

        for (run in runs.asReversed()) {
            val (start, end) = run
            if (start == 0) {
                text.delete(start, end)
                continue
            }
            var p = start - 1
            while (p >= 0 && text[p].isWs()) p--
            var n = end
            while (n < text.length && text[n].isWs()) n++
            val prevItem = if (p >= 0) innermostItem(p) else null
            val nextItem = if (n < text.length) innermostItem(n) else null
            when {
                prevItem != null && prevItem == nextItem -> Unit
                prevItem != null || nextItem != null -> text.delete(start, end)
            }
        }
    }

    // Trailing content right after </blockquote> would otherwise sit on the quote's last line.
    private fun separateBlockQuoteTrailingContent(text: SpanBuffer) {
        val quotes = text.spansOf<RichStyle.Blockquote>()
        if (quotes.isEmpty()) return
        fun Char.isTrimable() = this == '\n' || this == ' ' || this == '\t'
        val edits = ArrayList<Pair<Int, Int>>()
        for (quote in quotes) {
            val spanEnd = quote.end.coerceIn(0, text.length)
            var contentStart = spanEnd
            while (contentStart < text.length && text[contentStart].isTrimable()) contentStart++
            if (contentStart >= text.length) continue
            if (text.getSpans(contentStart, contentStart + 1).any { it.style is RichStyle.Blockquote }) continue
            edits.add(spanEnd to contentStart)
        }
        edits.sortByDescending { it.first }
        for ((start, end) in edits) {
            text.replace(start, end, "\n\n")
        }
    }

    // Senders pad blockquotes with blank lines that browsers collapse; drop the trimmable run at each end.
    private fun collapseBlockQuotePadding(text: SpanBuffer) {
        val quotes = text.spansOf<RichStyle.Blockquote>()
        if (quotes.isEmpty()) return
        fun Char.isTrimable() = this == '\n' || this == ' ' || this == '\t'
        val delete = BooleanArray(text.length)
        for (quote in quotes) {
            val start = quote.start.coerceAtLeast(0)
            val end = quote.end.coerceAtMost(text.length)
            var lead = start
            while (lead < end && text[lead].isTrimable()) { delete[lead] = true; lead++ }
            var trail = end
            while (trail > lead && text[trail - 1].isTrimable()) { delete[trail - 1] = true; trail-- }
        }
        var i = text.length
        while (i > 0) {
            if (delete[i - 1]) {
                val runEnd = i
                while (i > 0 && delete[i - 1]) i--
                text.delete(i, runEnd)
            } else {
                i--
            }
        }
    }

    companion object {
        /**
         * Markwon strands a newline in front of an inline element that follows a block (noties/Markwon#423);
         * the Android app removes it when the text is bound to a view, so display code must apply this.
         */
        fun removeLeadingNewlineForInlineElement(text: RichText): RichText {
            fun isInline(style: RichStyle) = when (style) {
                RichStyle.Italic, RichStyle.Bold, RichStyle.Underline, RichStyle.Strikethrough -> true
                is RichStyle.Link, is RichStyle.Url, is RichStyle.Emote, is RichStyle.Image, is RichStyle.Maths -> true
                is RichStyle.Code -> !style.isBlock
                else -> false
            }
            if (text.spans.none { isInline(it.style) && it.start in text.text.indices && text.text[it.start] == '\n' }) return text
            val buffer = text.toSpanBuffer()
            for (span in buffer.allSpans()) {
                if (!isInline(span.style)) continue
                val start = span.start
                if (start in 0 until buffer.length && buffer[start] == '\n') buffer.delete(start, start + 1)
            }
            return buffer.toRichText()
        }

        /** Removes the `<code>` bookkeeping spans, as Markwon's bind step does. */
        fun dropIntermediateSpans(text: RichText): RichText =
                text.copy(spans = text.spans.filterNot { it.style is RichStyle.IntermediateCode })

        /** Trims outer whitespace, except where a block span relies on it for line-height / margin math. */
        fun trimUncoveredWhitespace(text: RichText): RichText {
            fun Char.isTrimable() = this == '\n' || this == ' ' || this == '\t'
            val coveredRanges = text.spans.filter {
                when (it.style) {
                    RichStyle.Blockquote, is RichStyle.Code, is RichStyle.Heading, is RichStyle.LeadingMargin, is RichStyle.VerticalPadding -> true
                    else -> false
                }
            }.map { it.start to it.end }
            fun covered(at: Int) = coveredRanges.any { (s, e) -> at in s until e }
            val value = text.text
            var start = 0
            while (start < value.length && value[start].isTrimable() && !covered(start)) start++
            var end = value.length
            while (end > start && value[end - 1].isTrimable() && !covered(end - 1)) end--
            if (start == 0 && end == value.length) return text
            val buffer = text.toSpanBuffer()
            buffer.delete(end, buffer.length)
            buffer.delete(0, start)
            return buffer.toRichText()
        }
    }
}

fun RichText.toSpanBuffer(): SpanBuffer = SpanBuffer(text).also { buffer -> spans.forEach { buffer.setSpan(it.style, it.start, it.end) } }
