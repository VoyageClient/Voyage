/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.colorpicker

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.features.home.avatar.DefaultAvatarFactory
import im.vector.app.features.home.avatar.DefaultAvatarStyle
import im.vector.app.features.settings.AvatarShape
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.ThemeUtils
import javax.inject.Inject

/**
 * Picks what an avatar shows when there is none to load, previewing every style against the colors
 * the palette in use would give it.
 *
 * Result under [ARG_REQUEST_KEY]: [RESULT_STYLE] is the chosen style's name.
 */
@AndroidEntryPoint
class DefaultAvatarPickerDialogFragment : DialogFragment() {

    enum class Kind { PEOPLE, ROOM }

    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var defaultAvatarFactory: DefaultAvatarFactory

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
                    setFragmentResult(requireArguments().getString(ARG_REQUEST_KEY)!!, bundleOf(RESULT_STYLE to selected))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
    }

    private fun buildContent(context: Context): View {
        val density = resources.displayMetrics.density
        val kind = kind()
        val styles = if (kind == Kind.PEOPLE) DefaultAvatarStyle.PEOPLE else DefaultAvatarStyle.ROOM
        val colors = previewColors(context, kind)
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val buttons = mutableListOf<RadioButton>()
        styles.forEachIndexed { position, style ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (12 * density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            val button = RadioButton(context).apply {
                text = getString(style.titleRes)
                isChecked = style.name == selected
            }
            buttons.add(button)
            row.addView(button)
            row.addView(previewStrip(context, style, colors), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * density).toInt(); leftMargin = (32 * density).toInt() })
            row.setOnClickListener {
                selected = style.name
                buttons.forEachIndexed { index, other -> other.isChecked = index == position }
            }
            button.setOnClickListener { row.performClick() }
            rows.addView(row)
        }
        return ScrollView(context).apply { addView(rows) }
    }

    // One unbroken bar of square tiles, like the palette picker's swatches.
    private fun previewStrip(context: Context, style: DefaultAvatarStyle, colors: List<Int>): View {
        val maxCell = (32 * resources.displayMetrics.density).toInt()
        return SquareCellStrip(context, maxCell).apply {
            colors.forEachIndexed { index, color ->
                val preview = View(context).apply {
                    backgroundCompat = defaultAvatarFactory.create(style, letterAt(index), color, AvatarShape.SQUARE)
                }
                addView(preview, SquareCellStrip.cellParams())
            }
        }
    }

    private fun letterAt(index: Int) = ('A' + index).toString()

    // The colors this kind of avatar can get right now, so a preview matches what the app will draw.
    private fun previewColors(context: Context, kind: Kind): List<Int> {
        val light = ThemeUtils.isLightTheme(context)
        val palette = when (kind) {
            Kind.PEOPLE -> vectorPreferences.peopleColorPalette()
                    .takeIf { it != ColorPalette.NONE }
                    ?.colors
                    ?: vectorPreferences.roomColorPalette().colors
            Kind.ROOM -> vectorPreferences.roomColorPalette().colors
        }
        return palette.map { ContextCompat.getColor(context, it.forTheme(light)) }
    }

    private fun kind() = Kind.valueOf(requireArguments().getString(ARG_KIND)!!)

    companion object {
        private const val ARG_REQUEST_KEY = "requestKey"
        private const val ARG_TITLE = "title"
        private const val ARG_KIND = "kind"
        private const val ARG_SELECTED = "selected"
        private const val SAVE_SELECTED = "DefaultAvatarPickerDialogFragment.selected"

        const val RESULT_STYLE = "style"

        fun newInstance(requestKey: String, title: String, kind: Kind, selected: String) =
                DefaultAvatarPickerDialogFragment().apply {
                    arguments = bundleOf(
                            ARG_REQUEST_KEY to requestKey,
                            ARG_TITLE to title,
                            ARG_KIND to kind.name,
                            ARG_SELECTED to selected,
                    )
                }
    }
}
