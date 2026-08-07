/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import com.google.android.flexbox.FlexboxLayout
import im.vector.app.R
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.reactions.widget.ReactionButton

@EpoxyModelClass
abstract class DefaultItem : BaseEventItem<DefaultItem.Holder>(R.layout.item_timeline_event_base_noinfo) {

    @EpoxyAttribute
    lateinit var attributes: Attributes

    private val reactionClickListener = object : ReactionButton.ReactedListener {
        override fun onReacted(reactionButton: ReactionButton) {
            attributes.reactionPillCallback?.onClickOnReactionPill(attributes.informationData, reactionButton.reactionString, true)
        }

        override fun onUnReacted(reactionButton: ReactionButton) {
            attributes.reactionPillCallback?.onClickOnReactionPill(attributes.informationData, reactionButton.reactionString, false)
        }

        override fun onLongClick(reactionButton: ReactionButton) {
            attributes.reactionPillCallback?.onLongClickOnReactionPill(attributes.informationData, reactionButton.reactionString)
        }
    }

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.messageTextView.text = attributes.text.prepareForDisplay()
        attributes.avatarRenderer.render(attributes.informationData.matrixItem, holder.avatarImageView)
        holder.view.setOnLongClickListener(attributes.itemLongClickListener)

        ReactionsContainerRenderer.render(
                container = holder.reactionsContainer,
                reactionsSummary = attributes.informationData.reactionsSummary,
                hideMediaReactions = attributes.informationData.hideMediaReactions,
                reactedListener = reactionClickListener,
                reactionsSummaryEvents = attributes.reactionsSummaryEvents,
                longClickListener = attributes.itemLongClickListener,
        )
    }

    override fun getRowTintColor(context: Context): Int? = revealedRedactionTint(context, attributes.informationData)

    override fun unbind(holder: Holder) {
        attributes.avatarRenderer.clear(holder.avatarImageView)
        holder.reactionsContainer.setOnLongClickListener(null)
        super.unbind(holder)
    }

    override fun getEventIds(): List<String> {
        return listOf(attributes.informationData.eventId)
    }

    override fun getViewStubId() = STUB_ID

    class Holder : BaseHolder(STUB_ID) {
        val avatarImageView by bind<ImageView>(R.id.itemDefaultAvatarView)
        val messageTextView by bind<TextView>(R.id.itemDefaultTextView)
        val reactionsContainer by bind<FlexboxLayout>(R.id.reactionsContainer)
    }

    data class Attributes(
            val avatarRenderer: AvatarRenderer,
            val informationData: MessageInformationData,
            val text: CharSequence,
            val itemLongClickListener: View.OnLongClickListener? = null,
            val reactionPillCallback: TimelineEventController.ReactionPillCallback? = null,
            val reactionsSummaryEvents: ReactionsSummaryEvents? = null,
    )

    companion object {
        private val STUB_ID = R.id.messageContentDefaultStub
    }
}
