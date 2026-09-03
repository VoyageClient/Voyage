/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.colorpicker

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.features.themes.ThemeUtils

/**
 * Picks one palette out of a list, previewing every color it contains with its index drawn on it.
 *
 * Result under [ARG_REQUEST_KEY]: [RESULT_PALETTE] is the chosen entry's name.
 */
class PalettePickerDialogFragment : DialogFragment() {

    enum class Kind { PEOPLE, ROOM }

    private class PaletteChoice(val name: String, @StringRes val titleRes: Int, val colors: List<PaletteColor>)

    private var selected: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = savedInstanceState?.getString(SAVE_SELECTED) ?: requireArguments().getString(ARG_SELECTED)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SAVE_SELECTED, selected)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        return MaterialAlertDialogBuilder(context)
                .setTitle(requireArguments().getString(ARG_TITLE))
                .setView(buildContent(context))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    setFragmentResult(requireArguments().getString(ARG_REQUEST_KEY)!!, bundleOf(RESULT_PALETTE to selected))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
    }

    private fun buildContent(context: Context): View {
        val density = resources.displayMetrics.density
        val options = optionsOf(kind())
        val light = ThemeUtils.isLightTheme(context)
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val buttons = mutableListOf<RadioButton>()
        options.forEachIndexed { position, option ->
            val colors = option.colors.map { ContextCompat.getColor(context, it.forTheme(light)) }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (12 * density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            val button = RadioButton(context).apply {
                text = getString(option.titleRes)
                isChecked = option.name == selected
            }
            buttons.add(button)
            row.addView(button)
            if (colors.isNotEmpty()) {
                row.addView(swatchStrip(context, colors), LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * density).toInt(); leftMargin = (32 * density).toInt() })
            }
            row.setOnClickListener {
                selected = option.name
                buttons.forEachIndexed { index, other -> other.isChecked = index == position }
            }
            button.setOnClickListener { row.performClick() }
            rows.addView(row)
        }
        return ScrollView(context).apply { addView(rows) }
    }

    // One unbroken bar of square swatches, shrinking as the palette grows.
    private fun swatchStrip(context: Context, colors: List<Int>): View {
        val maxCell = (32 * resources.displayMetrics.density).toInt()
        return SquareCellStrip(context, maxCell).apply {
            colors.forEachIndexed { index, color ->
                addView(swatch(context, color, index + 1), SquareCellStrip.cellParams())
            }
        }
    }

    private fun swatch(context: Context, @ColorInt color: Int, label: Int): View {
        return TextView(context).apply {
            text = label.toString()
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            backgroundCompat = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(color)
            }
        }
    }

    private fun kind() = Kind.valueOf(requireArguments().getString(ARG_KIND)!!)

    companion object {
        private const val ARG_REQUEST_KEY = "requestKey"
        private const val ARG_TITLE = "title"
        private const val ARG_KIND = "kind"
        private const val ARG_SELECTED = "selected"
        private const val SAVE_SELECTED = "PalettePickerDialogFragment.selected"

        const val RESULT_PALETTE = "palette"

        /** Every palette, except that a room avatar always has a color, so it cannot be [ColorPalette.NONE]. */
        private fun optionsOf(kind: Kind): List<PaletteChoice> = ColorPalette.values()
                .filter { kind == Kind.PEOPLE || it != ColorPalette.NONE }
                .map { PaletteChoice(it.name, it.titleRes, it.colors) }

        fun newInstance(requestKey: String, title: String, kind: Kind, selected: String) =
                PalettePickerDialogFragment().apply {
                    arguments = bundleOf(
                            ARG_REQUEST_KEY to requestKey,
                            ARG_TITLE to title,
                            ARG_KIND to kind.name,
                            ARG_SELECTED to selected,
                    )
                }
    }
}
