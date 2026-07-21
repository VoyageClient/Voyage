/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

@file:Suppress("DEPRECATION")

package im.vector.app.core.ui.views

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import im.vector.app.features.emoji.TwemojiSpan
import im.vector.app.features.home.room.detail.timeline.tools.withEmojis
import im.vector.app.features.html.createChipLabelPaint
import kotlin.math.ceil

/**
 * A [Chip] whose label can contain Twemoji sprite emoji. ChipDrawable draws its text straight to
 * the canvas where spans can't render, so when the label spanifies to sprites this chip gives the
 * drawable empty text, widens itself by the label width, and draws the spanned label at the chip's
 * text position (same technique as PillImageSpan). Labels without sprite emoji behave exactly like
 * a plain Chip.
 */
class EmojiChip @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = com.google.android.material.R.attr.chipStyle,
) : Chip(context, attrs, defStyleAttr) {

    private var emojiLabel: CharSequence? = null
    private var labelPaint: TextPaint? = null
    private var labelLayout: StaticLayout? = null
    private var labelLayoutWidth = -1

    override fun setText(text: CharSequence?, type: BufferType?) {
        val spanified = text?.withEmojis()
        val needsManualLabel = (spanified as? Spanned)
                ?.getSpans(0, spanified.length, TwemojiSpan::class.java)?.isNotEmpty() == true
        emojiLabel = if (needsManualLabel) spanified else null
        labelLayout = null
        super.setText(if (needsManualLabel) "" else text, type)
    }

    private fun ensureLabelPaint(): TextPaint =
            labelPaint ?: createChipLabelPaint(context).also { labelPaint = it }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val label = emojiLabel ?: return
        val labelWidth = ceil(Layout.getDesiredWidth(label, ensureLabelPaint())).toInt()
        setMeasuredDimension(resolveSize(measuredWidth + labelWidth, widthMeasureSpec), measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val label = emojiLabel ?: return
        val chip = chipDrawable as? ChipDrawable ?: return
        val paint = ensureLabelPaint()
        val bounds = chip.bounds
        val iconWidth = if (chip.isChipIconVisible && chip.chipIcon != null) {
            chip.iconStartPadding + chip.chipIconSize + chip.iconEndPadding
        } else {
            0f
        }
        val closeIconWidth = if (chip.isCloseIconVisible && chip.closeIcon != null) {
            chip.closeIconStartPadding + chip.closeIconSize + chip.closeIconEndPadding
        } else {
            0f
        }
        val textStartX = bounds.left + chip.chipStartPadding + iconWidth + chip.textStartPadding
        val available =
                (bounds.right - textStartX - chip.textEndPadding - closeIconWidth - chip.chipEndPadding).toInt()
        if (available <= 0) return
        var layout = labelLayout
        if (layout == null || labelLayoutWidth != available) {
            val toDraw = if (ceil(Layout.getDesiredWidth(label, paint)).toInt() > available) {
                TextUtils.ellipsize(label, paint, available.toFloat(), TextUtils.TruncateAt.END)
            } else {
                label
            }
            layout = StaticLayout(toDraw, paint, available, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
            labelLayout = layout
            labelLayoutWidth = available
        }
        canvas.save()
        canvas.translate(textStartX, bounds.top + (bounds.height() - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
    }
}
