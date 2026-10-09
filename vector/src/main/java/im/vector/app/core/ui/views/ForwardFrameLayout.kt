/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.annotation.Px
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence

/** A forward's header and bar, drawn around a preview's media as the timeline draws them around the message. */
data class ForwardFrame(
        val header: EpoxyCharSequence,
        @Px val barWidth: Int,
        @Px val gap: Int,
        @ColorInt val color: Int,
)

/**
 * Stacks a preview's media, under a forward's header and beside its bar when it is one, so the frame the
 * timeline draws around a forwarded message also takes in its picture or player. The text below carries
 * the rest of the frame (ForwardedMessagePresenter.framedTail) and continues the bar.
 */
class ForwardFrameLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val headerView = AppCompatTextView(context).apply { visibility = GONE }
    private val barPaint = Paint()
    private var frame: ForwardFrame? = null

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        addView(headerView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun setForwardFrame(frame: ForwardFrame?) {
        if (this.frame == frame) return
        this.frame = frame
        headerView.text = frame?.header?.charSequence
        headerView.isVisible = frame != null
        ViewCompat.setPaddingRelative(this, frame?.let { it.barWidth + it.gap } ?: 0, 0, 0, 0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = frame ?: return
        val left = if (ViewCompat.getLayoutDirection(this) == ViewCompat.LAYOUT_DIRECTION_RTL) width - frame.barWidth else 0
        barPaint.color = frame.color
        canvas.drawRect(left.toFloat(), 0f, (left + frame.barWidth).toFloat(), height.toFloat(), barPaint)
    }
}
