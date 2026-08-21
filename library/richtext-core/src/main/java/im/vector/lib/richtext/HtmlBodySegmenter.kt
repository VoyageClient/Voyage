/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.richtext

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

sealed class BodySegment {
    data class Html(val html: String) : BodySegment()
    data class Table(val rows: List<TableRowData>) : BodySegment()
    data class Code(val code: String) : BodySegment()
}

data class TableRowData(val isHeader: Boolean, val cells: List<TableCellData>)
data class TableCellData(val html: String, val isHeader: Boolean, val alignment: Alignment)

enum class Alignment { LEFT, CENTER, RIGHT }

object HtmlBodySegmenter {

    fun segment(html: String): List<BodySegment> {
        if (!html.contains("<table", ignoreCase = true) && !html.contains("<pre", ignoreCase = true)) {
            return listOf(BodySegment.Html(html))
        }
        val doc = Jsoup.parseBodyFragment(html)
        val body = doc.body()
        val segments = mutableListOf<BodySegment>()
        val currentHtml = StringBuilder()
        for (node in body.childNodes()) {
            when {
                node is Element && node.tagName().equals("table", ignoreCase = true) -> {
                    flushHtml(segments, currentHtml)
                    segments += BodySegment.Table(parseTable(node))
                }
                node is Element && node.tagName().equals("pre", ignoreCase = true) -> {
                    flushHtml(segments, currentHtml)
                    segments += BodySegment.Code(parsePre(node))
                }
                else -> currentHtml.append(node.outerHtml())
            }
        }
        flushHtml(segments, currentHtml)
        return segments
    }

    // wholeText() keeps the original whitespace/newlines (unlike text(), which collapses them), so a
    // code block's indentation survives. Markdown wraps the code in <pre><code>…</code></pre>; fall
    // back to the <pre> text if there's no inner <code>. Drop a single trailing newline markdown adds.
    private fun parsePre(pre: Element): String {
        val codeEl = pre.selectFirst("code") ?: pre
        return codeEl.wholeText().removeSuffix("\n")
    }

    private fun flushHtml(out: MutableList<BodySegment>, buf: StringBuilder) {
        if (buf.isBlank()) {
            buf.clear()
            return
        }
        out += BodySegment.Html(buf.toString())
        buf.clear()
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
