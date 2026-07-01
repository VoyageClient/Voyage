/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan
import android.view.ContextThemeWrapper
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import im.vector.app.features.themes.ThemeUtils
import kotlin.math.ceil

/**
 * Inline pill (rounded media-pill background + icon + label) for an attachment, matching the reply
 * header's file/voice pill but usable in any TextView (long-press preview, reply composer) without a
 * dedicated view. Sized relative to the host text so it tracks the surrounding line height.
 */
class AttachmentPillSpan(
        context: Context,
        @DrawableRes iconRes: Int,
        private val label: CharSequence,
) : ReplacementSpan() {

    // Drawable inflation resolves the icon's `?attr` colours against the context theme; a ViewModel's
    // application context has no app theme, so wrap it with the current one (no-op for view contexts).
    private val themed = ContextThemeWrapper(context, ThemeUtils.getApplicationThemeRes(context))
    private val density = themed.resources.displayMetrics.density
    private val horizontalPadding = 8f * density
    private val iconLabelGap = 6f * density

    // DrawableCompat.wrap before setTint: tinting a framework GradientDrawable is a no-op pre-21, which
    // otherwise leaves bg_media_pill its native black on KitKat instead of the themed quinary.
    private val background = AppCompatResources.getDrawable(themed, im.vector.lib.ui.styles.R.drawable.bg_media_pill)?.mutate()
            ?.let { DrawableCompat.wrap(it) }?.apply {
                DrawableCompat.setTint(this, ThemeUtils.getColor(themed, im.vector.lib.ui.styles.R.attr.vctr_content_quinary))
            }
    private val icon = AppCompatResources.getDrawable(themed, iconRes)?.mutate()?.apply {
        DrawableCompat.setTint(this, ThemeUtils.getColor(themed, im.vector.lib.ui.styles.R.attr.vctr_content_secondary))
    }
    private val labelColor = ThemeUtils.getColor(themed, im.vector.lib.ui.styles.R.attr.vctr_content_primary)

    private var width = 0

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val iconSize = iconSize(paint)
        width = ceil(horizontalPadding + iconSize + iconLabelGap + paint.measureText(label, 0, label.length) + horizontalPadding).toInt()
        if (fm != null) {
            val pillHalf = (pillHeight(paint) / 2f).toInt()
            val center = (paint.fontMetricsInt.ascent + paint.fontMetricsInt.descent) / 2
            fm.ascent = center - pillHalf
            fm.top = fm.ascent
            fm.descent = center + pillHalf
            fm.bottom = fm.descent
        }
        return width
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val pillHeight = pillHeight(paint)
        val iconSize = iconSize(paint)
        val centerY = y + (paint.fontMetricsInt.ascent + paint.fontMetricsInt.descent) / 2f

        background?.setBounds(x.toInt(), (centerY - pillHeight / 2f).toInt(), (x + width).toInt(), (centerY + pillHeight / 2f).toInt())
        background?.draw(canvas)

        val iconLeft = x + horizontalPadding
        icon?.setBounds(iconLeft.toInt(), (centerY - iconSize / 2f).toInt(), (iconLeft + iconSize).toInt(), (centerY + iconSize / 2f).toInt())
        icon?.draw(canvas)

        val savedColor = paint.color
        paint.color = labelColor
        val baseline = centerY - (paint.fontMetricsInt.ascent + paint.fontMetricsInt.descent) / 2f
        canvas.drawText(label, 0, label.length, iconLeft + iconSize + iconLabelGap, baseline, paint)
        paint.color = savedColor
    }

    private fun pillHeight(paint: Paint) = paint.textSize * 1.6f
    private fun iconSize(paint: Paint) = paint.textSize * 1.1f
}
