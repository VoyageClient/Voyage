/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextUtils
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.TextViewCompat
import im.vector.app.core.utils.InlineCodePadding
import im.vector.app.core.utils.drawInlineCodeBackgrounds
import im.vector.app.features.html.HtmlCodeSpan

/** Plain TextView that draws inline code with the timeline's rounded, outlined panel. */
class InlineCodeTextView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    // Horizontal only: these are fixed-line previews whose height must not change with their content.
    private val inlineCodePadding by lazy { InlineCodePadding(this, includeVertical = false) }

    private var sourceText: CharSequence? = null
    private var truncatedFor: Pair<CharSequence, Int>? = null
    private var showingTruncated = false
    private var settingTruncated = false

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (!settingTruncated) {
            sourceText = text
            truncatedFor = null
            showingTruncated = false
        }
        super.setText(text, type)
    }

    override fun setMaxLines(maxLines: Int) {
        truncatedFor = null
        super.setMaxLines(maxLines)
    }

    override fun setLines(lines: Int) {
        truncatedFor = null
        super.setLines(lines)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        inlineCodePadding.update()
        truncateAfterCode(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        drawInlineCodeBackgrounds(canvas) { super.onDraw(canvas) }
    }

    // The framework's "…" sits flush against a code panel cut by the ellipsis, so when the cut lands in
    // code, truncate here instead and leave a gap. Otherwise the framework ellipsizes as usual.
    private fun truncateAfterCode(widthMeasureSpec: Int) {
        val source = sourceText as? Spanned ?: return
        if (ellipsize != TextUtils.TruncateAt.END || MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) return
        val width = MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight
        if (width <= 0 || truncatedFor?.let { it.first === source && it.second == width } == true) return
        truncatedFor = source to width
        val display = truncated(source, width)
        if (display == null && !showingTruncated) return
        showingTruncated = display != null
        settingTruncated = true
        try {
            setText(display ?: source)
        } finally {
            settingTruncated = false
        }
    }

    @Suppress("DEPRECATION") // StaticLayout.Builder is API 23+; line spacing doesn't affect where lines break.
    private fun truncated(source: Spanned, width: Int): CharSequence? {
        val maxLines = TextViewCompat.getMaxLines(this)
        if (maxLines <= 0) return null
        val full = StaticLayout(source, paint, width, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, true)
        if (full.lineCount <= maxLines) return null
        val lastLine = maxLines - 1
        val lineStart = full.getLineStart(lastLine)
        val codeSpans = source.getSpans(0, source.length, HtmlCodeSpan::class.java)
        val atomicSpans = source.getSpans(0, source.length, ReplacementSpan::class.java)

        fun Int.outsideAtomicRuns(): Int {
            var cut = this
            if (cut > 0 && cut < source.length && Character.isLowSurrogate(source[cut])) cut--
            atomicSpans.forEach {
                val start = source.getSpanStart(it)
                if (start < cut && cut < source.getSpanEnd(it)) cut = start
            }
            return cut
        }

        fun cutFitting(tail: String): Int {
            val tailWidth = paint.measureText(tail)
            var cut = full.getLineEnd(lastLine).outsideAtomicRuns()
            while (cut > lineStart) {
                while (cut > lineStart && source[cut - 1].isWhitespace()) cut--
                if (Layout.getDesiredWidth(source, lineStart, cut, paint) + tailWidth <= width) break
                cut = (cut - 1).outsideAtomicRuns()
            }
            return cut
        }

        fun touchesCode(cut: Int) = codeSpans.any { source.getSpanStart(it) < cut && cut <= source.getSpanEnd(it) }

        if (!touchesCode(cutFitting(ELLIPSIS))) return null
        val cut = cutFitting(CODE_GAP + ELLIPSIS)
        if (!touchesCode(cut)) return null
        return SpannableStringBuilder(source.subSequence(0, cut)).append(CODE_GAP).append(ELLIPSIS)
    }

    private companion object {
        const val ELLIPSIS = "…"

        // Matches the gap EventHtmlRenderer.spaceInlineCode leaves between inline code and adjacent text.
        const val CODE_GAP = " "
    }
}
