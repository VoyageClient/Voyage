/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.render

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.method.MovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.ViewCompat
import im.vector.app.R
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.html.Alignment
import im.vector.app.features.html.BodySegment
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.home.room.detail.timeline.tools.applySpoilerRenderLayer
import im.vector.app.features.html.TableCellData
import im.vector.app.features.html.TableRowData
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Populates a vertical LinearLayout with a mix of TextViews (HTML chunks rendered via Markwon)
 * and tables (HorizontalScrollView + TableLayout). Used when a message body contains <table>.
 *
 * Visual style inspired by element-web's github-markdown-css table: 1px borders, header row
 * background, generous cell padding, alternating row backgrounds, generous min cell width,
 * horizontal scroll when content overflows the available width.
 */
@Singleton
class RichMessageBodyRenderer @Inject constructor(
        private val htmlRenderer: dagger.Lazy<EventHtmlRenderer>,
        private val dim: DimensionConverter,
        private val vectorPreferences: VectorPreferences,
) {

    fun setTextWithPlugins(textView: android.widget.TextView, text: CharSequence?) {
        htmlRenderer.get().setTextWithPlugins(textView, text)
    }

    fun render(
            container: LinearLayout,
            segments: List<BodySegment>,
            postProcessors: Array<EventHtmlRenderer.PostProcessor>,
            movementMethod: MovementMethod?,
            onClick: (View) -> Unit,
            onLongClick: (View) -> Boolean,
            noticeStyle: Boolean = false,
            replyHeader: CharSequence? = null,
            // Previews (reply header / composer / long-press) are non-interactive: code blocks clip
            // overflow instead of scrolling, and show no scrollbar.
            interactive: Boolean = true,
            // Non-bubble timeline: stretch code blocks to the full row width (the bubble layout hugs
            // its content instead).
            fullBleed: Boolean = false,
    ) {
        val ctx = container.context
        val defaultColorAttr = if (noticeStyle) im.vector.lib.ui.styles.R.attr.vctr_content_secondary else im.vector.lib.ui.styles.R.attr.vctr_content_primary
        container.removeAllViews()
        if (replyHeader != null) {
            container.addView(buildReplyHeaderView(ctx, replyHeader, movementMethod, onClick, onLongClick))
        }
        segments.forEach { segment ->
            when (segment) {
                is BodySegment.Html -> container.addView(buildTextView(ctx, segment.html, postProcessors, movementMethod, onClick, onLongClick, defaultColorAttr))
                is BodySegment.Table -> container.addView(buildTable(ctx, segment.rows, postProcessors, movementMethod, defaultColorAttr))
                is BodySegment.Code -> container.addView(buildCodeBlock(ctx, segment.code, interactive, fullBleed, onClick, onLongClick))
            }
        }
    }

    private fun buildReplyHeaderView(
            ctx: Context,
            header: CharSequence,
            movementMethod: MovementMethod?,
            onClick: (View) -> Unit,
            onLongClick: (View) -> Boolean,
    ): AppCompatTextView {
        val tv = AppCompatTextView(ctx)
        tv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dim.dpToPx(4)
        }
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        tv.setTextColor(themeColor(ctx, im.vector.lib.ui.styles.R.attr.vctr_content_primary))
        tv.movementMethod = movementMethod
        tv.setOnClickListener(onClick)
        tv.setOnLongClickListener(onLongClick)
        htmlRenderer.get().setTextWithPlugins(tv, header)
        return tv
    }

    private fun buildTextView(
            ctx: Context,
            html: String,
            postProcessors: Array<EventHtmlRenderer.PostProcessor>,
            movementMethod: MovementMethod?,
            onClick: (View) -> Unit,
            onLongClick: (View) -> Boolean,
            defaultColorAttr: Int,
    ): AppCompatTextView {
        val tv = AppCompatTextView(ctx)
        tv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        tv.setTextColor(themeColor(ctx, defaultColorAttr))
        tv.movementMethod = movementMethod
        htmlRenderer.get().setTextWithPlugins(tv, htmlRenderer.get().render(html, *postProcessors))
        tv.applySpoilerRenderLayer()
        tv.setOnClickListener(onClick)
        tv.setOnLongClickListener(onLongClick)
        return tv
    }

    // Code block, element-web style: a rounded translucent panel with a left line-number gutter and the
    // monospace code preserving its indentation verbatim. In the timeline ([interactive]) long lines
    // scroll horizontally; in non-interactive previews they clip (no scroll view / scrollbar) so the
    // gesture stays with the surrounding long-press / list.
    private fun buildCodeBlock(
            ctx: Context,
            code: String,
            interactive: Boolean,
            fullBleed: Boolean,
            onClick: (View) -> Unit,
            onLongClick: (View) -> Boolean,
    ): View {
        val codeColor = themeColor(ctx, im.vector.lib.ui.styles.R.attr.vctr_content_primary)
        val gutterColor = themeColor(ctx, im.vector.lib.ui.styles.R.attr.vctr_content_tertiary)
        val lineCount = code.count { it == '\n' } + 1

        val outer = FullBleedLinearLayout(ctx).apply {
            this.fullBleed = fullBleed
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dim.dpToPx(6)
                bottomMargin = dim.dpToPx(6)
            }
            ViewCompat.setBackground(this, GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dim.dpToPx(6).toFloat()
                setColor(themeColor(ctx, im.vector.lib.ui.styles.R.attr.code_block_bg_color))
            })
            val padH = dim.dpToPx(10)
            val padV = dim.dpToPx(8)
            setPadding(padH, padV, padH, padV)
            setOnClickListener(onClick)
            setOnLongClickListener(onLongClick)
        }

        val gutter = AppCompatTextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, CODE_TEXT_SIZE_SP)
            setTextColor(gutterColor)
            gravity = Gravity.END
            setPadding(0, 0, dim.dpToPx(10), 0)
            text = (1..lineCount).joinToString("\n")
        }

        val codeView = AppCompatTextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, CODE_TEXT_SIZE_SP)
            setTextColor(codeColor)
            setHorizontallyScrolling(true)
            text = code
            setOnLongClickListener(onLongClick)
        }

        outer.addView(gutter)
        if (interactive) {
            codeView.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            val scroll = ShrinkableHorizontalScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                isHorizontalScrollBarEnabled = false
                isFillViewport = false
                addView(codeView)
            }
            outer.addView(scroll)
        } else {
            // Non-interactive: the code fills the remaining width and clips its overflow (the outer
            // LinearLayout clips children), so there's no scroll view to steal the gesture or draw a bar.
            codeView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            outer.addView(codeView)
        }
        return outer
    }

    private fun buildTable(
            ctx: Context,
            rows: List<TableRowData>,
            postProcessors: Array<EventHtmlRenderer.PostProcessor>,
            movementMethod: MovementMethod?,
            defaultColorAttr: Int,
    ): View {
        val scroll = ShrinkableHorizontalScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dim.dpToPx(6)
                bottomMargin = dim.dpToPx(6)
            }
            allowShrink = !vectorPreferences.isTableLineWrappingDisabled()
            isHorizontalScrollBarEnabled = true
            isFillViewport = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                scrollBarSize = dim.dpToPx(10)
            }
            isScrollbarFadingEnabled = true
        }
        val table = TableLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isShrinkAllColumns = false
            isStretchAllColumns = false
            setBackgroundResource(R.drawable.bg_rich_table_cell)
        }
        val colCount = rows.maxOfOrNull { it.cells.size } ?: 0
        rows.forEach { row ->
            table.addView(buildTableRow(ctx, row, colCount, postProcessors, movementMethod, defaultColorAttr))
        }
        scroll.addView(table)
        return scroll
    }

    private fun buildTableRow(
            ctx: Context,
            row: TableRowData,
            colCount: Int,
            postProcessors: Array<EventHtmlRenderer.PostProcessor>,
            movementMethod: MovementMethod?,
            defaultColorAttr: Int,
    ): TableRow {
        val tr = TableRow(ctx)
        tr.layoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.WRAP_CONTENT,
                TableLayout.LayoutParams.WRAP_CONTENT,
        )
        for (i in 0 until colCount) {
            val cell = row.cells.getOrNull(i)
            tr.addView(buildCellView(ctx, cell, row.isHeader, postProcessors, movementMethod, defaultColorAttr))
        }
        return tr
    }

    private fun buildCellView(
            ctx: Context,
            cell: TableCellData?,
            rowIsHeader: Boolean,
            postProcessors: Array<EventHtmlRenderer.PostProcessor>,
            movementMethod: MovementMethod?,
            defaultColorAttr: Int,
    ): AppCompatTextView {
        val isHeader = rowIsHeader || (cell?.isHeader == true)
        val tv = AppCompatTextView(ctx)
        val padH = dim.dpToPx(12)
        val padV = dim.dpToPx(8)
        tv.setPadding(padH, padV, padH, padV)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        tv.setTextColor(themeColor(ctx, defaultColorAttr))
        if (isHeader) {
            tv.setTypeface(tv.typeface, Typeface.BOLD)
            tv.setBackgroundResource(R.drawable.bg_rich_table_cell_header)
        } else {
            tv.setBackgroundResource(R.drawable.bg_rich_table_cell)
        }
        tv.movementMethod = movementMethod
        tv.gravity = when (cell?.alignment) {
            Alignment.CENTER -> Gravity.CENTER
            Alignment.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
            else -> Gravity.START or Gravity.CENTER_VERTICAL
        }
        tv.minWidth = dim.dpToPx(40)
        if (vectorPreferences.isTableLineWrappingDisabled()) {
            tv.setSingleLine(true)
            tv.ellipsize = null
        } else {
            tv.maxWidth = dim.dpToPx(560)
        }
        tv.layoutParams = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.MATCH_PARENT)
        val cellHtml = cell?.html?.trim().orEmpty()
        if (cellHtml.isEmpty()) tv.text = "" else htmlRenderer.get().setTextWithPlugins(tv, htmlRenderer.get().render(cellHtml, *postProcessors))
        return tv
    }

    private fun themeColor(ctx: Context, attrRes: Int): Int {
        val typedValue = TypedValue()
        ctx.theme.resolveAttribute(attrRes, typedValue, true)
        return typedValue.data
    }

    companion object {
        private const val CODE_TEXT_SIZE_SP = 14f
    }
}
