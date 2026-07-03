/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.content.Context
import android.graphics.Typeface
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat.getDrawable
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import im.vector.app.R
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.core.extensions.getDrawableAsSpannable
import im.vector.app.features.reactions.widget.ReactionButton
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonPlurals
import im.vector.lib.strings.CommonStrings

private const val MAX_REACTIONS_TO_SHOW = 8

// Renders a row of reaction pills into [container]. Shared by message and notice/system items. Reuses
// existing [ReactionButton] children rather than re-inflating every bind (inflation is costly on low-end).
object ReactionsContainerRenderer {

    fun render(
            container: ViewGroup,
            reactionsSummary: ReactionsSummaryData,
            hideMediaReactions: Boolean,
            reactedListener: ReactionButton.ReactedListener,
            reactionsSummaryEvents: ReactionsSummaryEvents?,
            longClickListener: View.OnLongClickListener?,
    ) {
        val reactions = reactionsSummary.reactions
        if (reactions.isNullOrEmpty()) {
            container.isVisible = false
            if (container.childCount > 0) container.removeAllViews()
            return
        }
        container.isVisible = true
        val reactionsToShow = if (reactionsSummary.showAll) reactions else reactions.take(MAX_REACTIONS_TO_SHOW)

        // Reuse existing ReactionButtons: drop trailing non-buttons (show-more / add) and surplus buttons first.
        while (container.childCount > 0 && container.getChildAt(container.childCount - 1) !is ReactionButton) {
            container.removeViewAt(container.childCount - 1)
        }
        while (container.childCount > reactionsToShow.size) {
            container.removeViewAt(container.childCount - 1)
        }
        reactionsToShow.forEachIndexed { index, reaction ->
            val reactionButton = (container.getChildAt(index) as? ReactionButton)
                    ?: ReactionButton(container.context).also { container.addView(it, index) }
            reactionButton.reactedListener = reactedListener
            reactionButton.setTag(R.id.reactionsContainer, reaction.key)
            reactionButton.blockImages = hideMediaReactions && !reaction.addedByMe
            reactionButton.reactionString = reaction.key
            reactionButton.reactionCount = reaction.count
            reactionButton.setChecked(reaction.addedByMe)
            reactionButton.isEnabled = reaction.synced
        }
        if (reactions.count() > MAX_REACTIONS_TO_SHOW) {
            val showReactionsTextView = createReactionTextView(container.context)
            if (reactionsSummary.showAll) {
                showReactionsTextView.setText(CommonStrings.message_reaction_show_less)
                showReactionsTextView.onClick { reactionsSummaryEvents?.onShowLessClicked?.invoke() }
            } else {
                val moreCount = reactions.count() - MAX_REACTIONS_TO_SHOW
                showReactionsTextView.text = container.resources.getQuantityString(CommonPlurals.message_reaction_show_more, moreCount, moreCount)
                showReactionsTextView.onClick { reactionsSummaryEvents?.onShowMoreClicked?.invoke() }
            }
            container.addView(showReactionsTextView)
        }
        val addMoreReactionsTextView = createReactionTextView(container.context)
        addMoreReactionsTextView.text = container.context.getDrawableAsSpannable(R.drawable.ic_add_reaction_small)
        addMoreReactionsTextView.onClick { reactionsSummaryEvents?.onAddMoreClicked?.invoke() }
        container.addView(addMoreReactionsTextView)
        container.setOnLongClickListener(longClickListener)
    }

    private fun createReactionTextView(context: Context): TextView {
        return TextView(ContextThemeWrapper(context, im.vector.lib.ui.styles.R.style.TimelineReactionView)).apply {
            backgroundCompat = getDrawable(this.context, R.drawable.reaction_rounded_rect_shape_off)?.mutate()?.also {
                // Theme attrs inside the drawable XML don't resolve pre-21; apply the fill in code.
                (it as? android.graphics.drawable.GradientDrawable)
                        ?.setColor(ThemeUtils.getColor(this.context, im.vector.lib.ui.styles.R.attr.vctr_reaction_background_off))
            }
            TextViewCompat.setTextAppearance(this, im.vector.lib.ui.styles.R.style.TextAppearance_Vector_Micro)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ThemeUtils.getColor(this.context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary))
        }
    }
}
