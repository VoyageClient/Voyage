/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.render

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Horizontal container for a rendered code block: line-number gutter + code text.
 *
 * When [fullBleed] is set, stretches to the full available width even inside a wrap_content
 * ancestor. Used so a code block fills the row in the non-bubble timeline (where the message
 * container hugs its content) instead of shrinking to the longest code line.
 *
 * When gutter syncing is wired via [syncGutterWithWrappedCode], the gutter is rebuilt during
 * measure: with line wrapping a logical code line can span several visual lines, so its number
 * sits on the first one and continuations stay blank. This must happen at measure time — a
 * post-layout setText needs a relayout to grow the gutter, and that requestLayout can be
 * swallowed (e.g. by RecyclerView mid-pass), leaving the taller gutter text clipped to the old
 * bounds.
 */
class CodeBlockLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var fullBleed = false

    private var gutterView: TextView? = null
    private var codeView: TextView? = null

    fun syncGutterWithWrappedCode(gutter: TextView, code: TextView) {
        gutterView = gutter
        codeView = code
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureWithFullBleed(widthMeasureSpec, heightMeasureSpec)
        val gutter = gutterView ?: return
        val layout = codeView?.layout ?: return
        val numbers = buildString {
            var lineNumber = 0
            for (visualLine in 0 until layout.lineCount) {
                if (visualLine > 0) append('\n')
                val start = layout.getLineStart(visualLine)
                if (start == 0 || layout.text[start - 1] == '\n') {
                    lineNumber++
                    append(lineNumber)
                }
            }
        }
        if (gutter.text.toString() != numbers) {
            gutter.text = numbers
            measureWithFullBleed(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun measureWithFullBleed(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (!fullBleed) return
        val mode = View.MeasureSpec.getMode(widthMeasureSpec)
        val size = View.MeasureSpec.getSize(widthMeasureSpec)
        if (mode != View.MeasureSpec.UNSPECIFIED && size > 0 && measuredWidth < size) {
            // Re-measure at the full width so the weighted code view fills it (weight only distributes
            // space in an EXACTLY spec), expanding the surrounding wrap_content container to the edge.
            super.onMeasure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), heightMeasureSpec)
        }
    }
}
