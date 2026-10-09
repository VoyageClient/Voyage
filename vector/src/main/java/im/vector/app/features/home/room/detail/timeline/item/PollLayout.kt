/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.content.Context
import android.text.Layout
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.Px
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import im.vector.app.R
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The root of `item_timeline_event_poll`. [compact], it is only as wide as its question and options, the
 * way block code hugs its content inside bubbles. Its children keep their fill constraints: it measures
 * their text itself and lays them out at exactly that width, since wrapping them breaks those constraints.
 */
class PollLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
) : ConstraintLayout(context, attrs) {

    private var compact = false

    /** [minWidth] applies only when filling the width. */
    fun setSizing(compact: Boolean, @Px minWidth: Int = 0) {
        val min = if (compact) 0 else minWidth
        if (this.compact == compact && this.minWidth == min) return
        this.compact = compact
        // Both: ConstraintLayout keeps its own minimum besides View's.
        this.minWidth = min
        minimumWidth = min
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!compact) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val content = contentWidth()
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            content
        } else {
            min(content, MeasureSpec.getSize(widthMeasureSpec))
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
    }

    private fun contentWidth(): Int {
        val icon = findViewById<View>(R.id.timelinePollIcon)
        val iconWidth = if (icon.isVisible) icon.layoutParams.width + icon.horizontalMargins() else 0
        var widest = maxOf(
                findViewById<TextView>(R.id.questionTextView).spanWidth() + iconWidth,
                findViewById<TextView>(R.id.endedPollTextView).spanWidth(),
                findViewById<TextView>(R.id.optionsVotesStatusTextView).spanWidth(),
        )
        val options = findViewById<ViewGroup>(R.id.optionsContainer)
        for (i in 0 until options.childCount) {
            val option = options.getChildAt(i)
            val check = option.findViewById<View>(R.id.optionCheckImageView)
            val checkWidth = if (check.isVisible) check.layoutParams.width + check.horizontalMargins() else 0
            val row = checkWidth +
                    option.findViewById<TextView>(R.id.optionNameTextView).spanWidth() +
                    option.findViewById<TextView>(R.id.optionVoteCountTextView).spanWidth()
            widest = max(widest, row + options.paddingLeft + options.paddingRight)
        }
        return widest + paddingLeft + paddingRight
    }

    // What the view takes on one line: its text, padding, compound drawables and margins.
    private fun TextView.spanWidth(): Int {
        if (!isVisible) return 0
        val text = ceil(Layout.getDesiredWidth(text ?: "", paint)).toInt()
        return text + compoundPaddingLeft + compoundPaddingRight + horizontalMargins()
    }

    private fun View.horizontalMargins(): Int {
        val params = layoutParams as? MarginLayoutParams ?: return 0
        return params.leftMargin + params.rightMargin
    }
}
