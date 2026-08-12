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
import im.vector.app.core.epoxy.onLongClickIgnoringLinksSelectingCode
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.core.utils.setReadOnlySelectable
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.tools.applySpoilerRenderLayer
import im.vector.app.features.home.room.detail.timeline.tools.linkify
import im.vector.app.features.html.Alignment
import im.vector.app.features.html.BodySegment
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.html.TableCellData
import im.vector.app.features.html.TableRowData
import im.vector.app.features.settings.VectorPreferences
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
            // Segments are rendered here rather than by the factory, so they need the same autolink
            // pass the single-TextView path applies to its body.
            urlClickCallback: TimelineEventController.UrlClickCallback? = null,
            // Previews (reply header / composer / long-press) are non-interactive: code blocks and
            // tables clip overflow instead of scrolling (no scroll view to steal the tap/gesture).
            interactive: Boolean = true,
            // Non-bubble timeline: stretch code blocks to the full row width (the bubble layout hugs
            // its content instead).
            fullBleed: Boolean = false,
    ) {
        val ctx = container.context
        val defaultColorAttr = if (noticeStyle) im.vector.lib.ui.styles.R.attr.vctr_content_secondary else im.vector.lib.ui.styles.R.attr.vctr_content_primary

        // Rebuilding the whole view tree (removeAllViews + re-add) on every bind is the dominant
        // timeline cost for tables/code; a rebind with unchanged body content (read receipts, reply
        // or sync updates) reuses the existing tree and only refreshes the per-bind click lambdas.
        val binding = (container.getTag(R.id.rich_body_binding) as? RichBodyBinding)
                ?: RichBodyBinding().also { container.setTag(R.id.rich_body_binding, it) }
        binding.onClick = onClick
        binding.onLongClick = onLongClick
        if (container.childCount > 0 &&
                binding.segments == segments &&
                binding.interactive == interactive &&
                binding.fullBleed == fullBleed &&
                binding.noticeStyle == noticeStyle &&
                binding.hasMovement == (movementMethod != null) &&
                binding.replyHeader?.toString() == replyHeader?.toString()) {
            return
        }
        binding.segments = segments
        binding.interactive = interactive
        binding.fullBleed = fullBleed
        binding.noticeStyle = noticeStyle
        binding.hasMovement = movementMethod != null
        binding.replyHeader = replyHeader

        container.removeAllViews()
        if (replyHeader != null) {
            container.addView(buildReplyHeaderView(ctx, replyHeader, movementMethod, binding))
        }
        segments.forEach { segment ->
            when (segment) {
                is BodySegment.Html ->
                    container.addView(buildTextView(ctx, segment.html, postProcessors, movementMethod, binding, defaultColorAttr, interactive, urlClickCallback))
                is BodySegment.Table ->
                    container.addView(buildTable(ctx, segment.rows, postProcessors, movementMethod, binding, defaultColorAttr, interactive, urlClickCallback))
                is BodySegment.Code -> container.addView(buildCodeBlock(ctx, segment.code, interactive, fullBleed, binding))
            }
        }
    }

    // Per-container render state: the current click lambdas (read at click time so an unchanged
    // tree needn't be re-wired) and the inputs the tree was last built from.
    private class RichBodyBinding {
        var onClick: (View) -> Unit = {}
        var onLongClick: (View) -> Boolean = { false }
        var segments: List<BodySegment>? = null
        var interactive = false
        var fullBleed = false
        var noticeStyle = false
        var hasMovement = false
        var replyHeader: CharSequence? = null
    }

    private fun buildReplyHeaderView(
            ctx: Context,
            header: CharSequence,
            movementMethod: MovementMethod?,
            binding: RichBodyBinding,
    ): AppCompatTextView {
        val tv = AppCompatTextView(ctx)
        tv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dim.dpToPx(4)
        }
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        tv.setTextColor(themeColor(ctx, im.vector.lib.ui.styles.R.attr.vctr_content_primary))
        tv.setOnClickListener { binding.onClick(it) }
        tv.setOnLongClickListener { binding.onLongClick(it) }
        htmlRenderer.get().setTextWithPlugins(tv, header)
        // After the plugin pass: Markwon's CorePlugin force-installs a LinkMovementMethod on a
        // movement-less view, so a deliberate null (inert links) has to be re-asserted.
        tv.movementMethod = movementMethod
        return tv
    }

    private fun buildTextView(
            ctx: Context,
            html: String,
            postProcessors: Array<EventHtmlRenderer.PostProcessor>,
            movementMethod: MovementMethod?,
            binding: RichBodyBinding,
            defaultColorAttr: Int,
            interactive: Boolean,
            urlClickCallback: TimelineEventController.UrlClickCallback?,
    ): AppCompatTextView {
        val tv = ReadOnlySelectableTextView(ctx)
        tv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        tv.setTextColor(themeColor(ctx, defaultColorAttr))
        htmlRenderer.get().setTextWithPlugins(tv, htmlRenderer.get().render(html, *postProcessors).linkify(urlClickCallback))
        tv.setReadOnlySelectable(interactive)
        // After the plugin pass, which would replace a deliberate null (see buildReplyHeaderView),
        // and after the selectable toggle, which installs its own movement method.
        tv.movementMethod = movementMethod
        tv.applySpoilerRenderLayer()
        tv.setOnClickListener { binding.onClick(it) }
        tv.onLongClickIgnoringLinksSelectingCode(View.OnLongClickListener { binding.onLongClick(it) })
        return tv
    }

    // Code block, element-web style: a rounded translucent panel with a left line-number gutter and the
    // monospace code preserving its indentation verbatim. With line wrapping enabled long lines wrap
    // (the gutter keeps its number on the logical line, blank on continuations); with it disabled they
    // scroll horizontally in the timeline ([interactive]) and clip in non-interactive previews (no
    // scroll view / scrollbar) so the gesture stays with the surrounding long-press / list.
    private fun buildCodeBlock(
            ctx: Context,
            code: String,
            interactive: Boolean,
            fullBleed: Boolean,
            binding: RichBodyBinding,
    ): View {
        val codeColor = themeColor(ctx, im.vector.lib.ui.styles.R.attr.vctr_content_primary)
        val gutterColor = themeColor(ctx, im.vector.lib.ui.styles.R.attr.vctr_content_tertiary)
        val lineCount = code.count { it == '\n' } + 1
        val wrap = vectorPreferences.isLineWrappingEnabled()

        val outer = CodeBlockLayout(ctx).apply {
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
            setOnClickListener { binding.onClick(it) }
            setOnLongClickListener { binding.onLongClick(it) }
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

        val codeView = (if (interactive) ReadOnlySelectableTextView(ctx, selectable = true) else AppCompatTextView(ctx)).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, CODE_TEXT_SIZE_SP)
            setTextColor(codeColor)
            setHorizontallyScrolling(!wrap)
            text = code
            setOnClickListener { binding.onClick(it) }
            if (!interactive) {
                // When selectable, a long-click listener would consume the press that starts selection;
                // the gutter/panel keeps the message menu reachable.
                setOnLongClickListener { binding.onLongClick(it) }
            }
        }

        outer.addView(gutter)
        when {
            wrap -> {
                codeView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                outer.syncGutterWithWrappedCode(gutter, codeView)
                outer.addView(codeView)
            }
            interactive -> {
                codeView.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
                val scroll = ShrinkableHorizontalScrollView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    isHorizontalScrollBarEnabled = false
                    isFillViewport = false
                    addView(codeView)
                }
                outer.addView(scroll)
            }
            else -> {
                // Non-interactive: the code fills the remaining width and clips its overflow (the outer
                // LinearLayout clips children), so there's no scroll view to steal the gesture or draw a bar.
                codeView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                outer.addView(codeView)
            }
        }
        return outer
    }

    private fun buildTable(
            ctx: Context,
            rows: List<TableRowData>,
            postProcessors: Array<EventHtmlRenderer.PostProcessor>,
            movementMethod: MovementMethod?,
            binding: RichBodyBinding,
            defaultColorAttr: Int,
            interactive: Boolean,
            urlClickCallback: TimelineEventController.UrlClickCallback?,
    ): View {
        val table = TableLayout(ctx).apply {
            isShrinkAllColumns = false
            isStretchAllColumns = false
            setBackgroundResource(R.drawable.bg_rich_table_cell)
        }
        val colCount = rows.maxOfOrNull { it.cells.size } ?: 0
        val cellRows = ArrayList<List<AppCompatTextView>>(rows.size)
        rows.forEach { row ->
            val rowCells = ArrayList<AppCompatTextView>(colCount)
            table.addView(buildTableRow(ctx, row, colCount, postProcessors, movementMethod, binding, defaultColorAttr, interactive, rowCells, urlClickCallback))
            cellRows.add(rowCells)
        }
        if (!interactive) {
            // Previews: no scroll view (it would steal the tap/gesture from the surrounding view).
            // The host measures the table at its natural size and clips the overflow.
            val host = PreviewTableHost(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dim.dpToPx(6)
                    bottomMargin = dim.dpToPx(6)
                }
                allowShrink = vectorPreferences.isLineWrappingEnabled()
            }
            host.addView(table)
            return host
        }
        val scroll = ShrinkableHorizontalScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dim.dpToPx(6)
                bottomMargin = dim.dpToPx(6)
            }
            allowShrink = vectorPreferences.isLineWrappingEnabled()
            isHorizontalScrollBarEnabled = true
            isFillViewport = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                scrollBarSize = dim.dpToPx(10)
            }
            isScrollbarFadingEnabled = true
        }
        table.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        scroll.addView(table)
        // Each cell exposes the whole table, so a cell selection's menu can offer "Copy table"
        cellRows.forEach { cells -> cells.forEach { (it as? ReadOnlySelectableTextView)?.tableCellRows = cellRows } }
        return scroll
    }

    private fun buildTableRow(
            ctx: Context,
            row: TableRowData,
            colCount: Int,
            postProcessors: Array<EventHtmlRenderer.PostProcessor>,
            movementMethod: MovementMethod?,
            binding: RichBodyBinding,
            defaultColorAttr: Int,
            interactive: Boolean,
            cellCollector: MutableList<AppCompatTextView>,
            urlClickCallback: TimelineEventController.UrlClickCallback?,
    ): TableRow {
        val tr = TableRow(ctx)
        tr.layoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.WRAP_CONTENT,
                TableLayout.LayoutParams.WRAP_CONTENT,
        )
        for (i in 0 until colCount) {
            val cell = row.cells.getOrNull(i)
            val cellView = buildCellView(ctx, cell, row.isHeader, postProcessors, movementMethod, binding, defaultColorAttr, interactive, urlClickCallback)
            cellCollector.add(cellView)
            tr.addView(cellView)
        }
        return tr
    }

    private fun buildCellView(
            ctx: Context,
            cell: TableCellData?,
            rowIsHeader: Boolean,
            postProcessors: Array<EventHtmlRenderer.PostProcessor>,
            movementMethod: MovementMethod?,
            binding: RichBodyBinding,
            defaultColorAttr: Int,
            interactive: Boolean,
            urlClickCallback: TimelineEventController.UrlClickCallback?,
    ): AppCompatTextView {
        val isHeader = rowIsHeader || (cell?.isHeader == true)
        val tv = ReadOnlySelectableTextView(ctx)
        val padH = dim.dpToPx(12)
        val padV = dim.dpToPx(8)
        tv.setPadding(padH, padV, padH, padV)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        tv.setTextColor(themeColor(ctx, defaultColorAttr))
        if (isHeader) {
            tv.setTypeface(tv.typeface, Typeface.BOLD)
            // Built in code: theme attrs in drawable XML don't resolve pre-21
            ViewCompat.setBackground(tv, GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(themeColor(ctx, im.vector.lib.ui.styles.R.attr.vctr_content_quinary))
                setStroke(dim.dpToPx(1), themeColor(ctx, im.vector.lib.ui.styles.R.attr.vctr_content_quaternary))
            })
        } else {
            tv.setBackgroundResource(R.drawable.bg_rich_table_cell)
        }
        tv.setOnClickListener { binding.onClick(it) }
        tv.onLongClickIgnoringLinksSelectingCode(View.OnLongClickListener { binding.onLongClick(it) })
        tv.gravity = when (cell?.alignment) {
            Alignment.CENTER -> Gravity.CENTER
            Alignment.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
            else -> Gravity.START or Gravity.CENTER_VERTICAL
        }
        tv.minWidth = dim.dpToPx(40)
        if (vectorPreferences.isLineWrappingEnabled()) {
            tv.maxWidth = dim.dpToPx(560)
        } else {
            tv.setSingleLine(true)
            tv.ellipsize = null
        }
        tv.layoutParams = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.MATCH_PARENT)
        val cellHtml = cell?.html?.trim().orEmpty()
        if (cellHtml.isEmpty()) {
            tv.text = ""
        } else {
            htmlRenderer.get().setTextWithPlugins(tv, htmlRenderer.get().render(cellHtml, *postProcessors).linkify(urlClickCallback))
        }
        tv.setReadOnlySelectable(interactive)
        // After the plugin pass, which would replace a deliberate null (see buildReplyHeaderView),
        // and after the selectable toggle, which installs its own movement method.
        tv.movementMethod = movementMethod
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
