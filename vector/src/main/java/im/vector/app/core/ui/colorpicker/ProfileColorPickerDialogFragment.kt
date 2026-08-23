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
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.EmojiSpanify
import im.vector.app.R
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.profile.ColorPreference
import javax.inject.Inject
import im.vector.lib.ui.styles.R as StylesR

/**
 * Grid of the name colors a user can get (the normal palette or the element-web one, starting on
 * whichever the "uglier palette" setting uses), plus a custom swatch that opens the HSV picker on
 * long press.
 *
 * Result under [ARG_REQUEST_KEY]: [RESULT_HEX] is the color for this dialog's theme, while
 * [RESULT_HEX_LIGHT]/[RESULT_HEX_DARK] carry both theme variants (they only differ for the
 * element-web palette). [RESULT_RESET] is set when the user asked to go back to the default.
 */
@AndroidEntryPoint
class ProfileColorPickerDialogFragment : DialogFragment() {

    enum class Theme { LIGHT, DARK, CURRENT }

    private sealed class Entry(val onLight: String, val onDark: String) {
        class Palette(@StringRes val nameRes: Int, onLight: String, onDark: String) : Entry(onLight, onDark)
        class Custom(hex: String) : Entry(hex, hex)
    }

    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var emojiSpanify: EmojiSpanify

    private val columnCount = 5

    private var light = true
    private var ugly = false
    private var customHex = DEFAULT_CUSTOM

    // Selection is an identity, not a hex: a custom color equal to a palette color must still read as
    // custom, and the default (nothing chosen) must not highlight a swatch that merely matches it.
    private var selectedHex: String? = null
    private var selectedIsCustom = false
    private var entries: List<Entry> = emptyList()

    private var gridLayout: LinearLayout? = null
    private var selectedLabel: TextView? = null
    private var paletteToggle: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        light = when (Theme.valueOf(args.getString(ARG_THEME)!!)) {
            Theme.LIGHT -> true
            Theme.DARK -> false
            Theme.CURRENT -> ThemeUtils.isLightTheme(requireContext())
        }
        val initial = ColorPreference.normalizeHex(args.getString(ARG_INITIAL_HEX))
        if (savedInstanceState != null) {
            ugly = savedInstanceState.getBoolean(SAVE_UGLY)
            customHex = savedInstanceState.getString(SAVE_CUSTOM_HEX) ?: customHex
            selectedHex = savedInstanceState.getString(SAVE_SELECTED_HEX)
            selectedIsCustom = savedInstanceState.getBoolean(SAVE_SELECTED_CUSTOM)
        } else {
            ugly = vectorPreferences.useUglierUsernameColors()
            customHex = vectorPreferences.lastCustomProfileColor()
            initFromInitial(initial)
        }
        childFragmentManager.setFragmentResultListener(HSV_REQUEST_KEY, this) { _, bundle ->
            val hex = bundle.getString(HsvColorPickerDialogFragment.RESULT_HEX) ?: return@setFragmentResultListener
            customHex = hex
            selectedHex = null
            selectedIsCustom = true
            vectorPreferences.setLastCustomProfileColor(hex)
            context?.let { populate(it) }
        }
    }

    // A set value that matches a palette color (in either palette) selects that swatch; otherwise it is
    // treated as a custom color. A null value leaves nothing selected (the default).
    private fun initFromInitial(initial: String?) {
        if (initial == null) return
        val inNormal = NORMAL_PALETTE.any { hexOf(it.second) == initial }
        val inUgly = UGLY_PALETTE.any { hexOf(if (light) it.second else it.third) == initial }
        when {
            inNormal -> { ugly = false; selectedHex = initial }
            inUgly -> { ugly = true; selectedHex = initial }
            else -> { customHex = initial; selectedIsCustom = true }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(SAVE_UGLY, ugly)
        outState.putString(SAVE_CUSTOM_HEX, customHex)
        outState.putString(SAVE_SELECTED_HEX, selectedHex)
        outState.putBoolean(SAVE_SELECTED_CUSTOM, selectedIsCustom)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val args = requireArguments()
        val builder = MaterialAlertDialogBuilder(context)
                .setCustomTitle(buildTitle(context, args.getString(ARG_TITLE).orEmpty()))
                .setView(buildContent(context))
                .setPositiveButton(android.R.string.ok) { _, _ -> deliver() }
                .setNegativeButton(android.R.string.cancel, null)
        if (args.getBoolean(ARG_SHOW_RESET)) {
            val label = if (args.getBoolean(ARG_RESET_IS_DELETE)) CommonStrings.action_delete else CommonStrings.room_personalization_reset
            builder.setNeutralButton(label) { _, _ ->
                setFragmentResult(args.getString(ARG_REQUEST_KEY)!!, bundleOf(RESULT_RESET to true, RESULT_THEME to args.getString(ARG_THEME)))
            }
        }
        return builder.create()
    }

    override fun onStart() {
        super.onStart()
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(
                ThemeUtils.getColor(requireContext(), com.google.android.material.R.attr.colorError)
        )
    }

    private fun deliver() {
        val entry = when {
            selectedIsCustom -> entries.lastOrNull { it is Entry.Custom }
            selectedHex != null -> entries.firstOrNull { it !is Entry.Custom && it.forTheme() == selectedHex }
            else -> null
        } ?: return
        setFragmentResult(
                requireArguments().getString(ARG_REQUEST_KEY)!!,
                bundleOf(
                        RESULT_HEX to entry.forTheme(),
                        RESULT_HEX_LIGHT to entry.onLight,
                        RESULT_HEX_DARK to entry.onDark,
                        RESULT_THEME to requireArguments().getString(ARG_THEME),
                )
        )
    }

    private fun Entry.forTheme() = if (light) onLight else onDark

    private fun buildTitle(context: Context, title: String): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((24 * density).toInt(), (20 * density).toInt(), (16 * density).toInt(), 0)
        }
        row.addView(TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(ThemeUtils.getColor(context, com.google.android.material.R.attr.colorOnSurface))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f })
        paletteToggle = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener {
                ugly = !ugly
                populate(context)
            }
        }
        row.addView(paletteToggle)
        return row
    }

    private fun buildContent(context: Context): View {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        gridLayout = grid
        root.addView(
                ScrollView(context).apply { addView(grid) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        selectedLabel = TextView(context).apply { setPadding(0, pad, 0, 0) }
        root.addView(selectedLabel)
        populate(context)
        return root
    }

    private fun hexOf(@ColorRes res: Int) = MatrixItemColorProvider.toHex(ContextCompat.getColor(requireContext(), res))

    private fun paletteEntries(ugly: Boolean): List<Entry.Palette> {
        return if (ugly) {
            UGLY_PALETTE.map { (name, lightRes, darkRes) -> Entry.Palette(name, hexOf(lightRes), hexOf(darkRes)) }
        } else {
            NORMAL_PALETTE.map { (name, res) -> Entry.Palette(name, hexOf(res), hexOf(res)) }
        }
    }

    private fun populate(context: Context) {
        val grid = gridLayout ?: return
        grid.removeAllViews()
        entries = paletteEntries(ugly) + Entry.Custom(customHex)
        paletteToggle?.text = emojiSpanify.spanify(getString(if (ugly) CommonStrings.color_picker_normal_palette else CommonStrings.color_picker_ugly_palette))

        val previewSize = resources.getDimensionPixelSize(R.dimen.color_matrix_list_preview_size)
        val selectionPadding = resources.getDimensionPixelSize(R.dimen.color_matrix_list_selection_padding)
        val borderColor = ThemeUtils.getColor(context, com.google.android.material.R.attr.colorOnSurface)

        var row: LinearLayout? = null
        var column = 0
        entries.forEach { entry ->
            if (row == null || column == columnCount) {
                row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                grid.addView(row)
                column = 0
            }
            val hex = entry.forTheme()
            val color = Color.parseColor(hex)
            val selected = if (entry is Entry.Custom) selectedIsCustom else !selectedIsCustom && hex == selectedHex
            val swatch: View = if (entry is Entry.Custom) customSwatch(context, color) else View(context).apply { setBackgroundColor(color) }
            val cell: View = if (selected) {
                swatch.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        .apply { setMargins(selectionPadding, selectionPadding, selectionPadding, selectionPadding) }
                FrameLayout(context).apply {
                    setBackgroundColor(borderColor)
                    addView(swatch)
                }
            } else {
                swatch
            }
            cell.layoutParams = LinearLayout.LayoutParams(0, previewSize).apply { weight = 1f }
            cell.setOnClickListener {
                if (entry is Entry.Custom) {
                    selectedIsCustom = true
                    selectedHex = null
                } else {
                    selectedIsCustom = false
                    selectedHex = hex
                }
                populate(context)
            }
            if (entry is Entry.Custom) {
                cell.setOnLongClickListener {
                    if (childFragmentManager.findFragmentByTag(HSV_DIALOG_TAG) == null) {
                        HsvColorPickerDialogFragment.newInstance(HSV_REQUEST_KEY, color).show(childFragmentManager, HSV_DIALOG_TAG)
                    }
                    true
                }
            }
            row!!.addView(cell)
            column++
        }
        // Pad the last row so its swatches keep the same width as the full rows.
        repeat(columnCount - column) {
            row!!.addView(View(context), LinearLayout.LayoutParams(0, previewSize).apply { weight = 1f })
        }
        updateSelectedLabel()
    }

    private fun customSwatch(context: Context, @ColorInt color: Int): View {
        val icon = ImageView(context).apply {
            val drawable = DrawableCompat.wrap(ContextCompat.getDrawable(context, R.drawable.ic_eyedropper)!!.mutate())
            DrawableCompat.setTint(drawable, if (ColorUtils.calculateLuminance(color) > 0.5) Color.BLACK else Color.WHITE)
            setImageDrawable(drawable)
            contentDescription = getString(CommonStrings.color_picker_custom)
        }
        return FrameLayout(context).apply {
            setBackgroundColor(color)
            addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    private fun updateSelectedLabel() {
        val label = selectedLabel ?: return
        val paletteEntry = selectedHex?.let { hex -> entries.firstOrNull { it is Entry.Palette && it.forTheme() == hex } as? Entry.Palette }
        label.text = when {
            selectedIsCustom -> getString(CommonStrings.color_picker_selected_custom, customHex)
            paletteEntry != null -> getString(CommonStrings.color_picker_selected_hex, getString(paletteEntry.nameRes), selectedHex!!)
            else -> getString(CommonStrings.color_picker_selected_default, requireArguments().getString(ARG_DEFAULT_HEX).orEmpty())
        }
    }

    override fun onDestroyView() {
        gridLayout = null
        selectedLabel = null
        paletteToggle = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_REQUEST_KEY = "requestKey"
        private const val ARG_INITIAL_HEX = "initialHex"
        private const val ARG_THEME = "theme"
        private const val ARG_TITLE = "title"
        private const val ARG_DEFAULT_HEX = "defaultHex"
        private const val ARG_SHOW_RESET = "showReset"
        private const val ARG_RESET_IS_DELETE = "resetIsDelete"
        private const val SAVE_UGLY = "ProfileColorPickerDialogFragment.ugly"
        private const val SAVE_CUSTOM_HEX = "ProfileColorPickerDialogFragment.customHex"
        private const val SAVE_SELECTED_HEX = "ProfileColorPickerDialogFragment.selectedHex"
        private const val SAVE_SELECTED_CUSTOM = "ProfileColorPickerDialogFragment.selectedCustom"
        private const val DEFAULT_CUSTOM = "#000000"
        private const val HSV_REQUEST_KEY = "ProfileColorPickerDialogFragment.hsv"
        private const val HSV_DIALOG_TAG = "HsvColorPickerDialog"

        const val RESULT_HEX = "hex"
        const val RESULT_HEX_LIGHT = "hexLight"
        const val RESULT_HEX_DARK = "hexDark"
        const val RESULT_RESET = "reset"
        const val RESULT_THEME = "theme"

        private val NORMAL_PALETTE = listOf(
                Pair(CommonStrings.profile_color_name_azure, StylesR.color.element_name_01),
                Pair(CommonStrings.profile_color_name_grape, StylesR.color.element_name_02),
                Pair(CommonStrings.profile_color_name_verde, StylesR.color.element_name_03),
                Pair(CommonStrings.profile_color_name_polly, StylesR.color.element_name_04),
                Pair(CommonStrings.profile_color_name_melon, StylesR.color.element_name_05),
                Pair(CommonStrings.profile_color_name_aqua, StylesR.color.element_name_06),
                Pair(CommonStrings.profile_color_name_prune, StylesR.color.element_name_07),
                Pair(CommonStrings.profile_color_name_kiwi, StylesR.color.element_name_08),
        )

        private val UGLY_PALETTE = listOf(
                Triple(CommonStrings.profile_color_ugly_green, StylesR.color.element_name_ew_light_01, StylesR.color.element_name_ew_dark_01),
                Triple(CommonStrings.profile_color_ugly_blue, StylesR.color.element_name_ew_light_02, StylesR.color.element_name_ew_dark_02),
                Triple(CommonStrings.profile_color_ugly_purple, StylesR.color.element_name_ew_light_03, StylesR.color.element_name_ew_dark_03),
                Triple(CommonStrings.profile_color_ugly_violet, StylesR.color.element_name_ew_light_04, StylesR.color.element_name_ew_dark_04),
                Triple(CommonStrings.profile_color_ugly_pink, StylesR.color.element_name_ew_light_05, StylesR.color.element_name_ew_dark_05),
                Triple(CommonStrings.profile_color_ugly_orange, StylesR.color.element_name_ew_light_06, StylesR.color.element_name_ew_dark_06),
        )

        fun newInstance(
                requestKey: String,
                title: String,
                initialHex: String?,
                defaultHex: String,
                theme: Theme = Theme.CURRENT,
                showReset: Boolean = false,
                resetIsDelete: Boolean = false,
        ): ProfileColorPickerDialogFragment {
            return ProfileColorPickerDialogFragment().apply {
                arguments = bundleOf(
                        ARG_REQUEST_KEY to requestKey,
                        ARG_TITLE to title,
                        ARG_INITIAL_HEX to initialHex,
                        ARG_DEFAULT_HEX to defaultHex,
                        ARG_THEME to theme.name,
                        ARG_SHOW_RESET to showReset,
                        ARG_RESET_IS_DELETE to resetIsDelete,
                )
            }
        }

        fun themeOf(bundle: Bundle): Theme = Theme.valueOf(bundle.getString(RESULT_THEME) ?: Theme.CURRENT.name)

        /** "Melon (#FF812D)", "Custom (#123456)" or, when [isDefault], "Default (#…)". */
        fun describe(context: Context, hex: String, light: Boolean, isDefault: Boolean): String {
            if (isDefault) return context.getString(CommonStrings.profile_color_summary_default, hex)
            val normalized = ColorPreference.normalizeHex(hex) ?: hex
            fun hexOf(@ColorRes res: Int) = MatrixItemColorProvider.toHex(ContextCompat.getColor(context, res))
            val name = NORMAL_PALETTE.firstOrNull { hexOf(it.second) == normalized }?.first
                    ?: UGLY_PALETTE.firstOrNull { hexOf(if (light) it.second else it.third) == normalized }?.first
            return if (name != null) {
                context.getString(CommonStrings.profile_color_summary_named, context.getString(name), normalized)
            } else {
                context.getString(CommonStrings.profile_color_summary_custom, normalized)
            }
        }

        /** The picked colors as a [ColorPreference], or null when the bundle is a reset. */
        fun resultToColorPreference(bundle: Bundle): ColorPreference? {
            if (bundle.getBoolean(RESULT_RESET)) return null
            return ColorPreference(bundle.getString(RESULT_HEX_LIGHT), bundle.getString(RESULT_HEX_DARK))
        }
    }
}
