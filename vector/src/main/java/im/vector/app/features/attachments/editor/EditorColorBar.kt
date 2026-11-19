/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor

import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.R
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.core.ui.colorpicker.HsvColorPickerView
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import java.util.Locale

/** A row of preset color swatches plus one opening a full picker, for the editors' draw tools. */
class EditorColorBar(
        private val container: LinearLayout,
        /** Themed for a dialog; the editors' own full-bleed theme cannot host one. */
        private val dialogContext: Context,
        /** The full picker was put away so a color can be taken from the image; answer with [resumeCustomPicker]. */
        private val onEyedropper: () -> Unit,
        private val onPicked: (Int) -> Unit,
) {

    private val density = container.resources.displayMetrics.density
    private val swatchSize = (30 * density).toInt()
    private val swatchMargin = (6 * density).toInt()
    private val swatches = mutableListOf<Pair<Int, View>>()
    private val customSwatch: View
    private var selected = Color.TRANSPARENT
    private var colorBeforeEyedropper = Color.BLACK

    init {
        PRESETS.forEach { color ->
            val swatch = View(container.context).apply {
                contentDescription = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)
                setOnClickListener { pick(color) }
            }
            swatches.add(color to swatch)
            container.addView(swatch, layoutParams())
        }
        customSwatch = View(container.context).apply {
            contentDescription = context.getString(CommonStrings.image_editor_color_custom)
            setOnClickListener { showCustomPicker() }
        }
        container.addView(customSwatch, layoutParams())
        render()
    }

    private fun layoutParams() = LinearLayout.LayoutParams(swatchSize, swatchSize).apply {
        leftMargin = swatchMargin
        rightMargin = swatchMargin
    }

    fun setSelected(color: Int) {
        selected = color
        render()
    }

    private fun pick(color: Int) {
        setSelected(color)
        onPicked(color)
    }

    private fun render() {
        val isPreset = PRESETS.contains(selected)
        swatches.forEach { (color, view) -> view.backgroundCompat = swatch(color, color == selected) }
        customSwatch.backgroundCompat = if (isPreset) {
            GradientDrawable(GradientDrawable.Orientation.TL_BR, HUES).apply {
                gradientType = GradientDrawable.SWEEP_GRADIENT
                shape = GradientDrawable.OVAL
                setStroke((1 * density).toInt(), UNSELECTED_RING)
            }
        } else {
            swatch(selected, true)
        }
    }

    private fun swatch(color: Int, isSelected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (isSelected) setStroke((3 * density).toInt(), Color.WHITE) else setStroke((1 * density).toInt(), UNSELECTED_RING)
    }

    /** Brings the full picker back with the color taken from the image, or as it was if [sampled] is null. */
    fun resumeCustomPicker(sampled: Int?) = showCustomPicker(sampled ?: colorBeforeEyedropper)

    private fun showCustomPicker(initial: Int = selected or 0xFF000000.toInt()) {
        val picker = HsvColorPickerView(dialogContext).apply { setColor(initial) }
        val padding = (16 * density).toInt()
        val frame = LinearLayout(dialogContext).apply {
            setPadding(padding, padding, padding, 0)
            addView(picker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (240 * density).toInt()))
        }
        val eyedropperIcon = AppCompatResources.getDrawable(dialogContext, R.drawable.ic_eyedropper)
        val dialog = MaterialAlertDialogBuilder(dialogContext)
                .setTitle(CommonStrings.image_editor_color_custom)
                .setView(frame)
                .setPositiveButton(android.R.string.ok) { _, _ -> pick(picker.getColor()) }
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("") { _, _ ->
                    colorBeforeEyedropper = picker.getColor()
                    onEyedropper()
                }
                // A textless button is only kept visible when it has an icon.
                .setNeutralButtonIcon(eyedropperIcon)
                .show()
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.let { button ->
            button.contentDescription = dialogContext.getString(CommonStrings.image_editor_color_eyedropper)
            // A plain icon, so it doesn't read as a third answer beside OK and Cancel.
            (button as? MaterialButton)?.apply {
                val tint = ThemeUtils.getColorFromContextTheme(dialogContext, im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
                icon = eyedropperIcon
                iconTint = ColorStateList.valueOf(tint)
                rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(tint, 0x33))
                iconPadding = 0
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                val touchTarget = (48 * density).toInt()
                minWidth = touchTarget
                minimumWidth = touchTarget
            }
        }
    }

    companion object {
        private const val UNSELECTED_RING = 0x80FFFFFF.toInt()

        const val RED = 0xFFE53935.toInt()

        val PRESETS = listOf(
                Color.WHITE,
                Color.BLACK,
                RED,
                0xFFFB8C00.toInt(),
                0xFFFDD835.toInt(),
                0xFF43A047.toInt(),
                0xFF1E88E5.toInt(),
                0xFF8E24AA.toInt(),
        )

        private val HUES = intArrayOf(
                Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
        )
    }
}
