/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.preference

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import im.vector.app.R
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings

@SuppressLint("ViewConstructor")
class ColorMatrixPickerView(
        context: Context,
        private val entries: Array<CharSequence>,
        private val entryValues: Array<CharSequence>,
        private val entryPreviews: Array<CharSequence>,
        selected: String?,
        private val trailingRowCount: Int,
        private val onSelection: (String) -> Unit,
) : LinearLayout(context) {
    private val columnCount = 5
    private var value = selected
    private val gridLayout: LinearLayout
    private val selectedLabel: TextView

    init {
        orientation = VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        val grid = LinearLayout(context).apply { orientation = VERTICAL }
        gridLayout = grid
        addView(ScrollView(context).apply { addView(grid) })
        selectedLabel = TextView(context).apply { setPadding(0, pad, 0, 0) }
        addView(selectedLabel)
        populate(context)
    }

    private fun updateSelectedLabel() {
        val index = entryValues.indexOfFirst { it.toString() == value }
        val name = entries.getOrNull(index) ?: return
        val hex = entryPreviews.getOrNull(index)?.toString()?.uppercase() ?: return
        selectedLabel.text = context.getString(CommonStrings.color_picker_selected_hex, name, hex)
    }

    private fun populate(context: Context) {
        val grid = gridLayout
        grid.removeAllViews()
        val previewSize = resources.getDimensionPixelSize(R.dimen.color_matrix_list_preview_size)
        val selectionPadding = resources.getDimensionPixelSize(R.dimen.color_matrix_list_selection_padding)
        val borderColor = ThemeUtils.getColorFromContextTheme(context, com.google.android.material.R.attr.colorOnSurface)

        val total = minOf(entryValues.size, entryPreviews.size)
        val gridded = (total - trailingRowCount).coerceAtLeast(0)
        // Spread the leftovers over the first rows rather than trailing a short row of one or two.
        val widenedRows = gridded % columnCount

        var i = 0
        var rowIndex = 0
        while (i < total) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            grid.addView(row)
            val onTrailingRow = i >= gridded
            val rowColumnCount = when {
                onTrailingRow -> trailingRowCount
                rowIndex < widenedRows -> columnCount + 1
                else -> columnCount
            }
            var column = 0
            while (column < rowColumnCount && i < total && (onTrailingRow || i < gridded)) {
                val entryValue = entryValues[i].toString()
                val color = Color.parseColor(entryPreviews[i].toString())
                val selected = entryValue == value

                val cellParams = LinearLayout.LayoutParams(0, previewSize).apply { weight = 1f }
                val cell: View = if (selected) {
                    val swatch = View(context).apply {
                        setBackgroundColor(color)
                        layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                        ).apply { setMargins(selectionPadding, selectionPadding, selectionPadding, selectionPadding) }
                    }
                    FrameLayout(context).apply {
                        setBackgroundColor(borderColor)
                        addView(swatch)
                    }
                } else {
                    View(context).apply { setBackgroundColor(color) }
                }
                cell.layoutParams = cellParams
                cell.setOnClickListener {
                    value = entryValue
                    onSelection(entryValue)
                    populate(context)
                }
                row.addView(cell)
                i++
                column++
            }
            if (onTrailingRow) {
                // Pad so the trailing swatches keep the same width as the ones in the grid above.
                repeat(columnCount - column) {
                    row.addView(View(context), LinearLayout.LayoutParams(0, previewSize).apply { weight = 1f })
                }
            }
            rowIndex++
        }
        updateSelectedLabel()
    }
}
