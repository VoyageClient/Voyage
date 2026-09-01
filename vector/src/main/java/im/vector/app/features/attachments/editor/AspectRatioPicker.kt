/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor

import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import android.widget.RadioButton
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.R
import im.vector.app.databinding.DialogAspectRatioBinding
import im.vector.lib.strings.CommonStrings
import kotlin.math.abs

/** Offers the usual ratios plus a freeform and a custom entry, for the crop or censor being edited. */
object AspectRatioPicker {

    /** Width to height, in the order they are offered. */
    private val PRESETS = listOf(1 to 1, 4 to 3, 16 to 9, 3 to 4, 9 to 16)
    private const val TOLERANCE = 0.001f
    private const val LARGEST_SIDE = 999999

    /**
     * [suggested] fills the custom boxes in; pass null when there is no meaningful ratio to
     * propose, as for a censor box.
     */
    fun show(
            context: Context,
            current: Float?,
            suggested: Pair<Int, Int>?,
            onPicked: (ratio: Float?, custom: Pair<Int, Int>?) -> Unit,
    ) {
        val inflater = LayoutInflater.from(context)
        val views = DialogAspectRatioBinding.inflate(inflater)
        // Freeform first, then the presets; each row's id is its index into this list.
        val options = listOf(null) + PRESETS
        options.forEachIndexed { index, preset ->
            val option = inflater.inflate(R.layout.item_aspect_ratio_option, views.aspectRatioPresets, false) as RadioButton
            option.id = index + 1
            option.text = preset?.let { "${it.first}:${it.second}" } ?: context.getString(CommonStrings.aspect_ratio_freeform)
            option.setOnClickListener { views.aspectRatioCustomOption.isChecked = false }
            views.aspectRatioPresets.addView(option)
        }
        // Custom sits outside the group, so the two keep each other honest on clicks. Not on checked
        // changes: clearCheck() reports the outgoing button's id, which reads as a preset being
        // picked and would undo the custom row that asked for the clear.
        views.aspectRatioCustomOption.setOnClickListener { views.aspectRatioPresets.clearCheck() }

        suggested?.let {
            views.aspectRatioWidth.setText(it.first.toString())
            views.aspectRatioHeight.setText(it.second.toString())
        }
        val checked = options.indexOfFirst { preset ->
            if (preset == null) current == null else current != null && abs(preset.first.toFloat() / preset.second - current) < TOLERANCE
        }
        if (checked >= 0) views.aspectRatioPresets.check(checked + 1) else views.aspectRatioCustomOption.isChecked = true

        val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(CommonStrings.aspect_ratio_title)
                .setView(views.root)
                .setPositiveButton(CommonStrings.ok, null)
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
        // Set after showing, so an unusable custom ratio can keep the dialog open instead of dismissing it.
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            if (views.aspectRatioCustomOption.isChecked) {
                val width = views.aspectRatioWidth.text.toString().toIntOrNull()
                val height = views.aspectRatioHeight.text.toString().toIntOrNull()
                if (width == null || height == null || width !in 1..LARGEST_SIDE || height !in 1..LARGEST_SIDE) {
                    views.aspectRatioError.setText(CommonStrings.aspect_ratio_invalid)
                    views.aspectRatioError.isVisible = true
                    return@setOnClickListener
                }
                onPicked(width.toFloat() / height, width to height)
            } else {
                val picked = options.getOrNull(views.aspectRatioPresets.checkedRadioButtonId - 1)
                onPicked(picked?.let { it.first.toFloat() / it.second }, null)
            }
            dialog.dismiss()
        }
    }
}

/** The ratio [width]:[height] in its smallest whole terms, for offering as a starting point. */
fun reduceRatio(width: Int, height: Int): Pair<Int, Int>? {
    if (width <= 0 || height <= 0) return null
    var a = width
    var b = height
    while (b != 0) {
        val remainder = a % b
        a = b
        b = remainder
    }
    return width / a to height / a
}
