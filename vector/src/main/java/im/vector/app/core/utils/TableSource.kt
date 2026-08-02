/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import android.text.Spanned
import android.widget.TextView

/** A table cell exposes its whole table as markdown, so the selection menu can offer "Copy table". */
interface TableSourceProvider {
    fun tableMarkdownSource(): String?
}

/** Reconstructs a GFM table from the rendered cell views: header row, `---` separator, body rows. */
fun buildTableMarkdown(cellRows: List<List<TextView>>): String = buildString {
    cellRows.forEachIndexed { index, cells ->
        append(cells.joinToString(" | ", "| ", " |") { cellSource(it) }).append('\n')
        if (index == 0) {
            append(cells.joinToString(" | ", "| ", " |") { "---" }).append('\n')
        }
    }
}.trimEnd('\n')

private fun cellSource(cell: TextView): String {
    val text = cell.text ?: return ""
    val source = (text as? Spanned)?.toMarkdownSource(0, text.length) ?: text.toString()
    return source.replace("\n", " ").replace("|", "\\|")
}
