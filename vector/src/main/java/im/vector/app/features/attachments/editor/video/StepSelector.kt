/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
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

    private val density = context.resources.displayMetrics.density
    private val rippleResource =
            ThemeUtils.getAttribute(context, androidx.appcompat.R.attr.selectableItemBackground)?.resourceId ?: 0

    var percentage = initial
        private set

    var isEnabled: Boolean = true
        set(value) {
            field = value
            buttons.forEach { it.isEnabled = value }
        }

    init {
        percentages.forEach { value ->
            val button = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                gravity = Gravity.CENTER
                text = context.getString(CommonStrings.video_editor_step_value, value)
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
        val ripple = rippleDrawable()
        val background = if (selected) LayerDrawable(listOfNotNull(dashedBorder(), ripple).toTypedArray()) else ripple
        ViewCompat.setBackground(button, background)
    }

    private fun rippleDrawable(): Drawable? =
            if (rippleResource == 0) null else ContextCompat.getDrawable(context, rippleResource)

    private fun dashedBorder(): Drawable = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        setStroke(density.toInt().coerceAtLeast(1), accent, DASH_DP * density, DASH_DP * density)
    }

    companion object {
        private const val DASH_DP = 4f
    }
}
