/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

sealed class BodySegment {
    data class Html(val html: String) : BodySegment()

    // These leave the spannable for views of their own, so a spoiler over one is drawn as a cover
    // rather than carried by the spoiler span.
    data class Table(val rows: List<TableRowData>, val spoiler: Boolean = false) : BodySegment()
    data class Code(val code: String, val spoiler: Boolean = false) : BodySegment()
}

data class TableRowData(val isHeader: Boolean, val cells: List<TableCellData>)
data class TableCellData(val html: String, val isHeader: Boolean, val alignment: Alignment)

enum class Alignment { LEFT, CENTER, RIGHT }

object HtmlBodySegmenter {

    private const val SPOILER_ATTRIBUTE = "data-mx-spoiler"

    fun segment(html: String): List<BodySegment> {
        if (!html.contains("<table", ignoreCase = true) && !html.contains("<pre", ignoreCase = true)) {
            return listOf(BodySegment.Html(html))
        }
        val doc = Jsoup.parseBodyFragment(html)
        val segments = mutableListOf<BodySegment>()
        val currentHtml = StringBuilder()
        collect(doc.body().childNodes(), segments, currentHtml, spoiler = null)
        flushHtml(segments, currentHtml, spoiler = null)
        return segments
    }

    /**
     * Splits [nodes] into inline runs and the blocks that need views of their own, descending through
     * spoiler wrappers so a code block or table inside one is still rendered as a code block or table.
     * [spoiler] is the wrapper's own tag, re-applied around the inline runs so they still blur.
     */
    private fun collect(nodes: List<Node>, segments: MutableList<BodySegment>, currentHtml: StringBuilder, spoiler: Element?) {
        for (node in nodes) {
            when {
                node is Element && node.tagName().equals("table", ignoreCase = true) -> {
                    flushHtml(segments, currentHtml, spoiler)
                    segments += BodySegment.Table(parseTable(node), spoiler = spoiler != null)
                }
                node is Element && node.tagName().equals("pre", ignoreCase = true) -> {
                    flushHtml(segments, currentHtml, spoiler)
                    segments += BodySegment.Code(parsePre(node), spoiler = spoiler != null)
                }
                // Only descend for a spoiler that actually wraps a block; anything else stays inline so
                // the spannable keeps the structure Markwon gives it.
                node is Element && spoiler == null && node.hasAttr(SPOILER_ATTRIBUTE) && node.wrapsBlock() -> {
                    flushHtml(segments, currentHtml, spoiler = null)
                    collect(node.childNodes(), segments, currentHtml, spoiler = node)
                    flushHtml(segments, currentHtml, spoiler = node)
                }
                else -> currentHtml.append(node.outerHtml())
            }
        }
    }

    private fun Element.wrapsBlock(): Boolean = select("pre, table").isNotEmpty()

    // wholeText() keeps the original whitespace/newlines (unlike text(), which collapses them), so a
    // code block's indentation survives. Markdown wraps the code in <pre><code>…</code></pre>; fall
    // back to the <pre> text if there's no inner <code>. Drop a single trailing newline markdown adds.
    private fun parsePre(pre: Element): String {
        val codeEl = pre.selectFirst("code") ?: pre
        return codeEl.wholeText().removeSuffix("\n")
    }

    private fun flushHtml(out: MutableList<BodySegment>, buf: StringBuilder, spoiler: Element?) {
        if (buf.isBlank()) {
            buf.clear()
            return
        }
        val html = buf.toString()
        // Re-wrap so the inline half of a spoiler still gets its span, with the reason it carried.
        out += BodySegment.Html(if (spoiler == null) html else spoiler.reopened(html))
        buf.clear()
    }

    private fun Element.reopened(inner: String): String {
        val attributes = attributes().joinToString("") { " ${it.key}=\"${Entities.escape(it.value)}\"" }
        return "<${tagName()}$attributes>$inner</${tagName()}>"
    }

    private fun parseTable(table: Element): List<TableRowData> {
        val rows = mutableListOf<TableRowData>()
        for (section in table.children()) {
            val tag = section.tagName().lowercase()
            when (tag) {
                "thead" -> section.children().toList().filter { it.tagName().equals("tr", true) }.forEach { rows += parseRow(it, headerSection = true) }
                "tbody", "tfoot" -> section.children().toList().filter { it.tagName().equals("tr", true) }.forEach { rows += parseRow(it, headerSection = false) }
                "tr" -> rows += parseRow(section, headerSection = false)
                else -> Unit
            }
        }
        return rows
    }

    private fun parseRow(tr: Element, headerSection: Boolean): TableRowData {
        val cells = mutableListOf<TableCellData>()
        for (cell in tr.children()) {
            val tag = cell.tagName().lowercase()
            if (tag != "td" && tag != "th") continue
            val alignment = alignmentFor(cell)
            cells += TableCellData(html = cell.html(), isHeader = (tag == "th"), alignment = alignment)
        }
        val isHeader = headerSection || (cells.isNotEmpty() && cells.all { it.isHeader })
        return TableRowData(isHeader, cells)
    }

    private fun alignmentFor(cell: Element): Alignment {
        val align = cell.attr("align").lowercase()
        if (align.isNotEmpty()) return when (align) {
            "center" -> Alignment.CENTER
            "right" -> Alignment.RIGHT
            else -> Alignment.LEFT
        }
        val style = cell.attr("style").lowercase()
        return when {
            "text-align:center" in style.filter { !it.isWhitespace() } -> Alignment.CENTER
            "text-align:right" in style.filter { !it.isWhitespace() } -> Alignment.RIGHT
            else -> Alignment.LEFT
        }
    }

    @Suppress("unused")
    private fun textOf(node: Node): String = when (node) {
        is TextNode -> node.text()
        is Element -> node.text()
        else -> ""
    }
}
