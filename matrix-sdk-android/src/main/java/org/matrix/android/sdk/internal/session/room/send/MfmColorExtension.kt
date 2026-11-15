/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import org.commonmark.ext.maths.DisplayMaths
import org.commonmark.ext.maths.InlineMaths
import org.commonmark.internal.InlineParserImpl
import org.commonmark.node.Code
import org.commonmark.node.CustomNode
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.InlineParser
import org.commonmark.parser.Parser
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlRenderer
import java.util.UUID

internal class MfmColor(val attributes: Map<String, String>) : CustomNode()

internal class MfmColorExtension : Parser.ParserExtension, HtmlRenderer.HtmlRendererExtension {
    override fun extend(parserBuilder: Parser.Builder) {
        parserBuilder.inlineParserFactory { context ->
            val delegate = InlineParserImpl(context)
            InlineParser { input, block ->
                if (input.contains("$[")) {
                    ColorInput(input).parse(delegate, block)
                } else {
                    delegate.parse(input, block)
                }
            }
        }
    }

    override fun extend(rendererBuilder: HtmlRenderer.Builder) {
        rendererBuilder.nodeRendererFactory { ColorRenderer(it) }
    }

    private class ColorRenderer(private val context: HtmlNodeRendererContext) : NodeRenderer {
        override fun getNodeTypes(): Set<Class<out Node>> = setOf(MfmColor::class.java)

        override fun render(node: Node) {
            context.writer.tag("span", (node as MfmColor).attributes)
            var child = node.firstChild
            while (child != null) {
                context.render(child)
                child = child.next
            }
            context.writer.tag("/span")
        }
    }

    // Commonmark 0.13 has no custom inline-token API. Markers keep its code, link and escape handling
    // while preventing MFM's opening dollar from being consumed as a maths delimiter.
    private class ColorInput(private val source: String) {
        private val prefix = "VOYAGEMFMCOLOR" + UUID.randomUUID().toString().replace("-", "")
        private val colors = mutableListOf<Pair<ColorMarker, ColorMarker>>()
        private val markers = mutableListOf<ColorMarker>()
        private val closingBrackets = findClosingBrackets()

        private val markerRegex = Regex("⟦${prefix}([0-9]+)⟧")

        fun parse(delegate: InlineParser, block: Node) {
            delegate.parse(protect(0, source.length, 0), block)
            restore(block, true)
            colors.asReversed().forEach { (open, close) ->
                if (open.parent != null && open.parent === close.parent) {
                    val color = MfmColor(open.attributes)
                    open.insertAfter(color)
                    var child = color.next
                    while (child != null && child !== close) {
                        val next = child.next
                        color.appendChild(child)
                        child = next
                    }
                    open.unlink()
                    close.unlink()
                } else {
                    open.restore()
                    close.restore()
                }
            }
        }

        private fun protect(start: Int, end: Int, depth: Int): String = buildString {
            var index = start
            while (index < end) {
                val codeEnd = if (source[index] == '`') backtickEnd(index, end) else null
                if (source[index] == '\\' && index + 1 < end) {
                    append(source, index, index + 2)
                    index += 2
                } else if (codeEnd != null) {
                    append(source, index, codeEnd)
                    index = codeEnd
                } else {
                    val head = if (depth < MAX_DEPTH && source.startsWith("$[", index)) {
                        COLOR_HEAD.matchAt(source, index)?.takeIf { it.range.last < end }
                    } else null
                    val close = head?.let { closingBrackets[index + 1]?.takeIf { it < end } }
                    if (head != null && close != null && source.substring(head.range.last + 1, close).isNotBlank()) {
                        val attributes = COLOR_ARGUMENT.findAll(head.value).associate { argument ->
                            val digits = argument.groupValues[2].lowercase()
                            val hex = if (digits.length == 3) digits.map { "$it$it" }.joinToString("") else digits
                            val name = if (argument.groupValues[1] == "fg") "data-mx-color" else "data-mx-bg-color"
                            name to "#$hex"
                        }
                        val openMarker = ColorMarker(head.value, attributes)
                        val closeMarker = ColorMarker("]")
                        colors += openMarker to closeMarker
                        append(token(openMarker))
                        append(protect(head.range.last + 1, close, depth + 1))
                        append(token(closeMarker))
                        index = close + 1
                    } else {
                        append(source[index++])
                    }
                }
            }
        }

        private fun findClosingBrackets(): Map<Int, Int> {
            val result = mutableMapOf<Int, Int>()
            val stack = mutableListOf<Int>()
            var index = 0
            while (index < source.length) {
                when (source[index]) {
                    '\\' -> index++
                    '`' -> index = backtickEnd(index, source.length) - 1
                    '[' -> stack += index
                    ']' -> if (stack.isNotEmpty()) result[stack.removeAt(stack.lastIndex)] = index
                }
                index++
            }
            return result
        }

        private fun backtickEnd(start: Int, end: Int): Int {
            var runEnd = start
            while (runEnd < end && source[runEnd] == '`') runEnd++
            var index = runEnd
            while (index < end) {
                if (source[index] != '`') {
                    index++
                    continue
                }
                val runStart = index
                while (index < end && source[index] == '`') index++
                if (index - runStart == runEnd - start) return index
            }
            return runEnd
        }

        private fun token(marker: ColorMarker): String {
            markers += marker
            return "⟦$prefix${markers.lastIndex}⟧"
        }

        private fun literal(text: String): String = markerRegex.replace(text) { markers[it.groupValues[1].toInt()].source }

        private fun restore(node: Node, allowColor: Boolean) {
            val allowed = allowColor && node !is Image && node !is InlineMaths && node !is DisplayMaths
            when (node) {
                is Text -> {
                    var start = 0
                    markerRegex.findAll(node.literal).forEach { match ->
                        if (match.range.first > start) node.insertBefore(Text(node.literal.substring(start, match.range.first)))
                        val marker = markers[match.groupValues[1].toInt()]
                        node.insertBefore(if (allowed) marker else Text(marker.source))
                        start = match.range.last + 1
                    }
                    node.literal = node.literal.substring(start)
                    if (node.literal.isEmpty()) node.unlink()
                }
                is Code -> node.literal = literal(node.literal)
                is HtmlInline -> node.literal = literal(node.literal)
                is Link -> {
                    node.destination = literal(node.destination)
                    node.title = node.title?.let { literal(it) }
                }
                is Image -> {
                    node.destination = literal(node.destination)
                    node.title = node.title?.let { literal(it) }
                }
            }
            var child = node.firstChild
            while (child != null) {
                val next = child.next
                restore(child, allowed)
                child = next
            }
        }
    }

    private class ColorMarker(val source: String, val attributes: Map<String, String> = emptyMap()) : CustomNode() {
        fun restore() {
            if (parent != null) {
                insertBefore(Text(source))
                unlink()
            }
        }
    }

    private companion object {
        const val MAX_DEPTH = 32
        const val HEX = "#?([0-9a-fA-F]{6}|[0-9a-fA-F]{3})(?![0-9a-fA-F])"
        val COLOR_HEAD = Regex("""\$\[(?:(?:fg|bg)\.color=$HEX[ \t]+)+""")
        val COLOR_ARGUMENT = Regex("""(fg|bg)\.color=$HEX""")
    }
}
