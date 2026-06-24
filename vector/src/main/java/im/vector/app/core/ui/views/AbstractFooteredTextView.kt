/*
 * Copyright 2021-2024 SchildiChat and New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.graphics.Canvas
import android.graphics.Rect
import android.text.Layout
import android.text.Spannable
import android.text.Spanned
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.text.getSpans
import androidx.core.text.toSpanned
import im.vector.app.features.html.HtmlCodeSpan
import io.noties.markwon.core.spans.EmphasisSpan
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * TextView that reserves space at the bottom (or in the last line) for overlaying it with a footer,
 * e.g. an inline timestamp in a message bubble.
 */
interface AbstractFooteredTextView {

    fun getAppCompatTextView(): AppCompatTextView
    fun setMeasuredDimensionExposed(measuredWidth: Int, measuredHeight: Int)

    val footerState: FooterState

    var footerHeight: Int
        get() = footerState.footerHeight
        set(value) { footerState.footerHeight = value }
    var footerWidth: Int
        get() = footerState.footerWidth
        set(value) { footerState.footerWidth = value }

    // When set (non-bubble layout), a message containing a block code span stretches to the full
    // available width so the code background runs to the edge instead of hugging the longest line.
    var fullWidthBlockCode: Boolean
        get() = footerState.fullWidthBlockCode
        set(value) { footerState.fullWidthBlockCode = value }

    class FooterState {
        var footerHeight: Int = 0
        var footerWidth: Int = 0
        var fullWidthBlockCode: Boolean = false

        // Some Rect to use during draw, since we should not alloc it during draw
        val testBounds = Rect()

        // Workaround to RTL languages with non-RTL content messages aligning left instead of start
        var requiredHorizontalCanvasMove = 0f
    }

    fun updateDimensionsWithFooter(widthMeasureSpec: Int, heightMeasureSpec: Int): Pair<Int, Int> = with(getAppCompatTextView()) {
        // Default case
        footerState.requiredHorizontalCanvasMove = 0f

        val layout = layout ?: return Pair(measuredWidth, measuredHeight)

        // Get max available width
        val widthLimit = View.MeasureSpec.getSize(widthMeasureSpec).toFloat()

        val lastLine = layout.lineCount - 1
        if (lastLine < 0) return Pair(measuredWidth, measuredHeight)

        // Let's check if the last line's text has the same RTL behaviour as the layout direction.
        val viewIsRtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val looksLikeRtl = layout.getParagraphDirection(lastLine) == Layout.DIR_RIGHT_TO_LEFT

        // Get required width for all lines
        var maxLineWidth = 0f
        for (i in 0 until layout.lineCount) {
            maxLineWidth = if (layout.getParagraphDirection(i) == Layout.DIR_RIGHT_TO_LEFT) {
                layout.getLineBounds(i, footerState.testBounds)
                max((footerState.testBounds.right - footerState.testBounds.left).toFloat(), maxLineWidth)
            } else {
                max(layout.getLineWidth(i), maxLineWidth)
            }
        }
        maxLineWidth = min(maxLineWidth, measuredWidth.toFloat())

        var newWidth = ceil(maxLineWidth).toInt()
        var newHeight = measuredHeight

        val widthLastLine = layout.getLineWidth(lastLine)

        // Required width if putting footer in the same line as the last line
        val widthWithHorizontalFooter = (
                if (looksLikeRtl == viewIsRtl) {
                    widthLastLine
                } else {
                    maxLineWidth + resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_footer_rtl_mismatch_extra_padding)
                }
                ) + footerState.footerWidth

        // If the last line is a multi-line code block, we never have space in the last line
        val forceNewlineFooter: Boolean
        // For italic text, we need some extra space due to a wrap_content bug: https://stackoverflow.com/q/4353836
        val addItalicPadding: Boolean
        val hasBlockCode: Boolean

        if (text is Spannable || text is Spanned) {
            val span = text.toSpanned()
            val lastLineStart = span.lastIndexOf("\n") + 1
            val lastLineCodeSpans = span.getSpans<HtmlCodeSpan>(lastLineStart)
            forceNewlineFooter = lastLineCodeSpans.any { it.isBlock }
            addItalicPadding = span.getSpans<EmphasisSpan>().isNotEmpty()
            hasBlockCode = span.getSpans<HtmlCodeSpan>().any { it.isBlock }
        } else {
            forceNewlineFooter = false
            addItalicPadding = false
            hasBlockCode = false
        }

        // Is there space for a horizontal footer?
        if (widthWithHorizontalFooter <= widthLimit && !forceNewlineFooter) {
            // Reserve extra horizontal footer space if necessary
            if (widthWithHorizontalFooter > newWidth) {
                newWidth = ceil(widthWithHorizontalFooter).toInt()

                if (viewIsRtl) {
                    footerState.requiredHorizontalCanvasMove = widthWithHorizontalFooter - measuredWidth
                }
            }
        } else {
            // Reserve vertical footer space
            newHeight += footerState.footerHeight
            // Small gap between wrapped text and the footer below it, but not after a code block
            // (its background already provides bottom spacing, which otherwise looks like too much padding).
            if (!forceNewlineFooter) {
                newHeight += ceil(4 * resources.displayMetrics.density).toInt()
            }
            // Ensure enough width for footer below
            newWidth = max(
                    newWidth,
                    footerState.footerWidth +
                            resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_footer_padding_compensation) +
                            2 * resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_footer_overlay_padding)
            )
        }

        if (addItalicPadding) {
            newWidth += resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.italic_text_view_extra_padding)
        }

        // Safety margin: getLineWidth() can under-report the last glyph's advance, which clips it once we
        // shrink the view to the measured line width.
        newWidth += ceil(2 * resources.displayMetrics.density).toInt()

        // Outside bubbles, stretch a block-code message to the full available width so its background
        // reaches the edge rather than only wrapping the longest line.
        if (footerState.fullWidthBlockCode && hasBlockCode) {
            newWidth = max(newWidth, ceil(widthLimit).toInt())
        }

        Pair(newWidth, newHeight)
    }

    fun updateFooterOnPreDraw(canvas: Canvas?) {
        // Workaround to RTL languages with non-RTL content messages aligning left instead of start
        if (footerState.requiredHorizontalCanvasMove > 0f) {
            canvas?.translate(footerState.requiredHorizontalCanvasMove, 0f)
        }
    }
}
