/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.preference

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceDialogFragmentCompat
import im.vector.app.R
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings

/**
 * Dialog showing the [ColorMatrixListPreference] entries as a tappable grid of color swatches.
 * Tapping a swatch selects it (inset with a border); the choice is only applied when the user taps OK.
 */
class ColorMatrixListPreferenceDialogFragment : PreferenceDialogFragmentCompat() {

    private val columnCount = 5

    private var value: String? = null
    private var trailingRowCount = 0
    private lateinit var entries: Array<CharSequence>
    private lateinit var entryValues: Array<CharSequence>
    private lateinit var entryPreviews: Array<CharSequence>

    private var gridLayout: LinearLayout? = null
    private var selectedLabel: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pref = preference as ColorMatrixListPreference
        value = savedInstanceState?.getString(SAVE_STATE_VALUE) ?: pref.value
        entries = pref.entries
        entryValues = pref.entryValues
        entryPreviews = pref.entryPreviews
                ?: throw IllegalStateException("ColorMatrixListPreference requires an entryPreviews array.")
        trailingRowCount = pref.trailingRowCount
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SAVE_STATE_VALUE, value)
    }

    override fun onCreateDialogView(context: Context): View {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val gap = (16 * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Padding below the dialog title and around the content.
            setPadding(pad, pad, pad, pad)
        }

        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        gridLayout = grid
        root.addView(
                ScrollView(context).apply { addView(grid) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        selectedLabel = TextView(context).apply {
            setPadding(0, gap, 0, 0)
        }
        root.addView(selectedLabel)

        populate(context)
        return root
    }

    private fun updateSelectedLabel() {
        val index = entryValues.indexOfFirst { it.toString() == value }
        val name = entries.getOrNull(index) ?: return
        val hex = entryPreviews.getOrNull(index)?.toString()?.uppercase() ?: return
        selectedLabel?.text = getString(CommonStrings.color_picker_selected_hex, name, hex)
    }

    private fun populate(context: Context) {
        val grid = gridLayout ?: return
        grid.removeAllViews()
        val previewSize = resources.getDimensionPixelSize(R.dimen.color_matrix_list_preview_size)
        val selectionPadding = resources.getDimensionPixelSize(R.dimen.color_matrix_list_selection_padding)
        val borderColor = ThemeUtils.getColor(context, com.google.android.material.R.attr.colorOnSurface)

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

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)
        builder.setPositiveButton(android.R.string.ok, this)
        builder.setNegativeButton(android.R.string.cancel, this)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        gridLayout = null
        selectedLabel = null
        val pref = preference as ColorMatrixListPreference
        val selected = value
        if (positiveResult && selected != null) {
            if (pref.callChangeListener(selected)) {
                pref.value = selected
            }
        }
    }

    companion object {
        private const val SAVE_STATE_VALUE = "ColorMatrixListPreferenceDialogFragment.value"

        fun newInstance(key: String): ColorMatrixListPreferenceDialogFragment {
            return ColorMatrixListPreferenceDialogFragment().apply {
                arguments = Bundle(1).apply { putString(ARG_KEY, key) }
            }
        }
    }
}
