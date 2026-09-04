/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.colorpicker

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.features.home.avatar.DefaultAvatarFactory
import im.vector.app.features.home.avatar.DefaultAvatarStyle
import im.vector.app.features.home.avatar.effect.AvatarEffect
import im.vector.app.features.home.avatar.effect.AvatarEffectDrawables
import im.vector.app.features.settings.AvatarShape
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

/**
 * Picks the shape avatars are drawn in, previewing each one as the app would draw it.
 *
 * Result under [ARG_REQUEST_KEY]: [RESULT_SHAPE] is the chosen shape's storage key.
 */
@AndroidEntryPoint
class AvatarShapePickerDialogFragment : DialogFragment() {

    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var defaultAvatarFactory: DefaultAvatarFactory

    private var selected: String? = null
    private val tiles = mutableMapOf<String, View>()
    private val previews = mutableListOf<Drawable>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = savedInstanceState?.getString(SAVE_SELECTED) ?: requireArguments().getString(ARG_SELECTED)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SAVE_SELECTED, selected)
    }

    // A preview set as a view's background is never told the dialog went away, so it would keep
    // rendering frames nobody draws.
    override fun onStart() {
        super.onStart()
        previews.forEach { (it as? Animatable)?.start() }
    }

    override fun onStop() {
        previews.forEach { (it as? Animatable)?.stop() }
        super.onStop()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        return MaterialAlertDialogBuilder(context)
                .setTitle(requireArguments().getString(ARG_TITLE))
                .setView(buildContent(context))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    setFragmentResult(requireArguments().getString(ARG_REQUEST_KEY)!!, bundleOf(RESULT_SHAPE to selected))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
    }

    private fun buildContent(context: Context): View {
        val density = resources.displayMetrics.density
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        addSection(context, column, CommonStrings.settings_avatar_shape_section_static, AvatarShape.STATIC)
        AvatarEffect.Family.values().forEach { family ->
            addSection(context, column, family.titleRes, AvatarShape.ANIMATED.filter { it.effect?.family == family })
        }
        return ScrollView(context).apply { addView(column) }
    }

    private fun addSection(context: Context, column: LinearLayout, titleRes: Int, shapes: List<AvatarShape>) {
        if (shapes.isEmpty()) return
        column.addView(header(context, titleRes))
        column.addView(grid(context, shapes), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }

    // Text colours come from the dialog's own theme. Resolving them against this context by hand
    // lands white on white in a light theme.
    private fun header(context: Context, titleRes: Int) = TextView(context).apply {
        text = getString(titleRes)
        setTypeface(typeface, Typeface.BOLD)
        val density = resources.displayMetrics.density
        setPadding(0, (12 * density).toInt(), 0, (6 * density).toInt())
    }

    private fun grid(context: Context, shapes: List<AvatarShape>): View {
        val density = resources.displayMetrics.density
        val cell = (56 * density).toInt()
        val gap = (6 * density).toInt()
        return GridLayout(context).apply {
            columnCount = COLUMNS
            shapes.forEach { shape ->
                addView(tile(context, shape, cell), GridLayout.LayoutParams().apply {
                    width = cell
                    setMargins(gap, gap, gap, gap)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED)
                })
            }
        }
    }

    private fun tile(context: Context, shape: AvatarShape, cell: Int): View {
        val density = resources.displayMetrics.density
        val preview = View(context).apply { backgroundCompat = previewDrawable(shape, cell) }
        val label = TextView(context).apply {
            text = getString(shape.titleRes)
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 2
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(preview, ViewGroup.LayoutParams(cell, cell))
            addView(label, LinearLayout.LayoutParams(cell, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2 * density).toInt()
            })
            tiles[shape.storageKey] = this
            setOnClickListener {
                selected = shape.storageKey
                refreshSelection()
            }
            refreshSelection(this, shape)
        }
    }

    // Drawn by the code the app draws avatars with, over the colour the active palette would give
    // this one. Animated shapes preview moving, since how they move is the difference between them.
    private fun previewDrawable(shape: AvatarShape, cell: Int) = when (val effect = shape.effect) {
        null -> defaultAvatarFactory.create(DefaultAvatarStyle.ELEMENT, "A", previewColor(), shape)
        else -> AvatarEffectDrawables.animatedPreview(
                defaultAvatarFactory.create(DefaultAvatarStyle.ELEMENT, "A", previewColor(), AvatarShape.SQUARE),
                effect,
                cell,
        ).also { previews.add(it) }
    }

    private fun previewColor(): Int {
        val light = ThemeUtils.isLightTheme(requireContext())
        val palette = vectorPreferences.peopleColorPalette()
                .takeIf { it != ColorPalette.NONE }
                ?: vectorPreferences.roomColorPalette()
        val swatch = palette.colors.firstOrNull() ?: return ThemeUtils.getColor(requireContext(), com.google.android.material.R.attr.colorPrimary)
        return ContextCompat.getColor(requireContext(), swatch.forTheme(light))
    }

    private fun refreshSelection() {
        AvatarShape.values().forEach { shape -> tiles[shape.storageKey]?.let { refreshSelection(it, shape) } }
    }

    private fun refreshSelection(tile: View, shape: AvatarShape) {
        tile.isSelected = shape.storageKey == selected
        tile.alpha = if (tile.isSelected) 1f else 0.55f
    }

    companion object {
        private const val ARG_REQUEST_KEY = "requestKey"
        private const val ARG_TITLE = "title"
        private const val ARG_SELECTED = "selected"
        private const val SAVE_SELECTED = "AvatarShapePickerDialogFragment.selected"
        private const val COLUMNS = 4

        const val RESULT_SHAPE = "shape"

        fun newInstance(requestKey: String, title: String, selected: String) =
                AvatarShapePickerDialogFragment().apply {
                    arguments = bundleOf(
                            ARG_REQUEST_KEY to requestKey,
                            ARG_TITLE to title,
                            ARG_SELECTED to selected,
                    )
                }
    }
}
