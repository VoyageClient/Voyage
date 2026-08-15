/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings

/** The row of step sizes the speed and volume sheets both offer, added to an existing label row. */
class StepSelector(
        private val container: LinearLayout,
        private val percentages: List<Int>,
        initial: Int,
        private val onSelected: (Int) -> Unit,
) {

    private val context = container.context
    private val buttons = mutableListOf<TextView>()
    private val accent = ThemeUtils.getColorFromContextTheme(context, com.google.android.material.R.attr.colorAccent)
    private val normal = ThemeUtils.getColorFromContextTheme(context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary)

    var percentage = initial
        private set

    var isEnabled: Boolean = true
        set(value) {
            field = value
            buttons.forEach { it.isEnabled = value }
        }

    init {
        val padding = (STEP_PADDING_DP * context.resources.displayMetrics.density).toInt()
        percentages.forEach { value ->
            val button = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
                setPadding(0, padding, 0, padding)
                text = context.getString(CommonStrings.video_editor_step_value, value)
                // AppCompat's, not the framework's: the borderless variant is API 21 in android:.
                setBackgroundResource(
                        ThemeUtils.getAttribute(context, androidx.appcompat.R.attr.selectableItemBackgroundBorderless)?.resourceId ?: 0
                )
                setOnClickListener {
                    percentage = value
                    render()
                    onSelected(value)
                }
            }
            buttons.add(button)
            container.addView(button)
        }
        render()
    }

    private fun render() = buttons.forEachIndexed { index, button ->
        val selected = percentages[index] == percentage
        button.setTextColor(if (selected) accent else normal)
        button.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    companion object {
        private const val STEP_PADDING_DP = 12
    }
}
