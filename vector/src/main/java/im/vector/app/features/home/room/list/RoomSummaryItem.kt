/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list

import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.extensions.applyThemeShapeColorCompat
import im.vector.app.core.extensions.clearDrawables
import im.vector.app.core.extensions.setRedactedPreviewStyle
import im.vector.app.core.extensions.setTextOrHide
import im.vector.app.core.ui.views.PresenceStateImageView
import im.vector.app.core.ui.views.ShieldImageView
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.RoomListDisplayMode
import im.vector.app.features.home.room.detail.timeline.tools.applySpoilerRenderLayer
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.html.bindEmoteImageSpans
import im.vector.app.features.html.bindPillImageSpans
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import org.matrix.android.sdk.api.session.crypto.model.RoomEncryptionTrustLevel
import org.matrix.android.sdk.api.session.presence.model.UserPresence
import org.matrix.android.sdk.api.util.MatrixItem

@EpoxyModelClass
abstract class RoomSummaryItem : VectorEpoxyModel<RoomSummaryItem.Holder>(R.layout.item_room) {

    @EpoxyAttribute
    lateinit var typingMessage: String

    @EpoxyAttribute
    lateinit var avatarRenderer: AvatarRenderer

    @EpoxyAttribute
    lateinit var matrixItem: MatrixItem

    @EpoxyAttribute
    var displayMode: RoomListDisplayMode = RoomListDisplayMode.PEOPLE

    @EpoxyAttribute
    lateinit var subtitle: String

    @EpoxyAttribute
    lateinit var lastFormattedEvent: EpoxyCharSequence

    @EpoxyAttribute
    var lastEventRedacted: Boolean = false

    @EpoxyAttribute
    lateinit var lastEventTime: String

    @EpoxyAttribute
    var encryptionTrustLevel: RoomEncryptionTrustLevel? = null

    @EpoxyAttribute
    var userPresence: UserPresence? = null

    @EpoxyAttribute
    var showPresence: Boolean = false

    @EpoxyAttribute
    var izPublic: Boolean = false

    @EpoxyAttribute
    var izPgp: Boolean = false

    @EpoxyAttribute
    var unreadNotificationCount: Int = 0

    @EpoxyAttribute
    var hasUnreadMessage: Boolean = false

    @EpoxyAttribute
    var hasDraft: Boolean = false

    @EpoxyAttribute
    var showHighlighted: Boolean = false

    @EpoxyAttribute
    var hasFailedSending: Boolean = false

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var itemLongClickListener: View.OnLongClickListener? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var itemClickListener: ClickListener? = null

    @EpoxyAttribute
    var showSelected: Boolean = false

    @EpoxyAttribute
    var showCheckbox: Boolean = false

    @EpoxyAttribute
    var useSingleLineForLastEvent: Boolean = false

    @EpoxyAttribute
    var loadMentionAvatars: Boolean = true

    override fun bind(holder: Holder) {
        val perfMarker = im.vector.app.core.utils.PerfTrace.mark("roomlist.item.bind")
        super.bind(holder)

        renderDisplayMode(holder)
        holder.rootView.onClick { view ->
            flipCheckboxOptimistically(holder)
            itemClickListener?.invoke(view)
        }
        holder.rootView.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            flipCheckboxOptimistically(holder)
            itemLongClickListener?.onLongClick(it) ?: false
        }
        holder.titleView.text = matrixItem.getBestName().prepareForDisplay()
        holder.unreadCounterBadgeView.render(UnreadCounterBadgeView.State.Count(unreadNotificationCount, showHighlighted))
        holder.unreadIndentIndicator.isVisible = hasUnreadMessage
        holder.draftView.isVisible = hasDraft
        avatarRenderer.render(matrixItem, holder.avatarImageView, decodeSize = im.vector.lib.ui.styles.R.dimen.room_avatar_decode_size)
        // PGP-on rooms show the padlock in the encryption-shield slot (same place olm rooms show theirs).
        holder.roomAvatarDecorationImageView.renderRoomShield(encryptionTrustLevel, izPgp)
        holder.roomAvatarPublicDecorationImageView.isVisible = izPublic
        holder.roomAvatarPublicDecorationImageView.applyThemeShapeColorCompat(android.R.attr.colorBackground)
        holder.roomAvatarFailSendingImageView.isVisible = hasFailedSending
        renderSelection(holder, showSelected)
        holder.roomAvatarPresenceImageView.render(showPresence, userPresence)

        if (useSingleLineForLastEvent) {
            holder.subtitleView.setLines(1)
        }
        perfMarker.end()
    }

    private fun renderDisplayMode(holder: Holder) {
        // Reset before dispatching: only the default mode styles a redacted preview, and holders are
        // shared with the filtered mode.
        holder.subtitleView.setTextColor(ThemeUtils.getColor(holder.view.context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary))
        holder.subtitleView.clearDrawables()
        when (displayMode) {
            RoomListDisplayMode.ROOMS,
            RoomListDisplayMode.PEOPLE,
            RoomListDisplayMode.ALL,
            RoomListDisplayMode.NOTIFICATIONS -> renderForDefaultDisplayMode(holder)
            RoomListDisplayMode.FILTERED -> renderForFilteredDisplayMode(holder)
        }
    }

    private fun renderForDefaultDisplayMode(holder: Holder) {
        holder.subtitleView.text = lastFormattedEvent.charSequence
        if (lastEventRedacted) holder.subtitleView.setRedactedPreviewStyle()
        holder.subtitleView.bindEmoteImageSpans()
        if (loadMentionAvatars) holder.subtitleView.bindPillImageSpans()
        // A spoiler in the preview keeps its blur; the BlurMaskFilter only paints on a software layer.
        holder.subtitleView.applySpoilerRenderLayer()
        holder.lastEventTimeView.text = lastEventTime
        holder.typingView.setTextOrHide(typingMessage.prepareForDisplay())
        holder.subtitleView.isInvisible = holder.typingView.isVisible
    }

    private fun renderForFilteredDisplayMode(holder: Holder) {
        holder.subtitleView.text = subtitle.prepareForDisplay()
    }

    override fun unbind(holder: Holder) {
        holder.rootView.setOnClickListener(null)
        holder.rootView.setOnLongClickListener(null)
        avatarRenderer.clear(holder.avatarImageView)
        super.unbind(holder)
    }

    // Rebuilding the whole list to reflect the new selection takes a beat, so the tick is flipped
    // up front and the rebuild confirms it.
    private fun flipCheckboxOptimistically(holder: Holder) {
        if (showCheckbox) holder.selectionCheckBox.renderRoomSelectionCheckbox(!showSelected)
    }

    private fun renderSelection(holder: Holder, isSelected: Boolean) {
        holder.selectionCheckBox.isVisible = showCheckbox
        if (showCheckbox) {
            holder.selectionCheckBox.renderRoomSelectionCheckbox(isSelected)
        }
    }

    class Holder : VectorEpoxyHolder() {
        val titleView by bind<TextView>(R.id.roomNameView)
        val unreadCounterBadgeView by bind<UnreadCounterBadgeView>(R.id.roomUnreadCounterBadgeView)
        val unreadIndentIndicator by bind<View>(R.id.roomUnreadIndicator)
        val subtitleView by bind<TextView>(R.id.subtitleView)
        val typingView by bind<TextView>(R.id.roomTypingView)
        val draftView by bind<ImageView>(R.id.roomDraftBadge)
        val lastEventTimeView by bind<TextView>(R.id.roomLastEventTimeView)
        val selectionCheckBox by bind<ImageView>(R.id.roomSelectionCheckBox)
        val avatarImageView by bind<ImageView>(R.id.roomAvatarImageView)
        val roomAvatarDecorationImageView by bind<ShieldImageView>(R.id.roomAvatarDecorationImageView)
        val roomAvatarPublicDecorationImageView by bind<ImageView>(R.id.roomAvatarPublicDecorationImageView)
        val roomAvatarFailSendingImageView by bind<ImageView>(R.id.roomAvatarFailSendingImageView)
        val roomAvatarPresenceImageView by bind<PresenceStateImageView>(R.id.roomAvatarPresenceImageView)
        val rootView by bind<ConstraintLayout>(R.id.itemRoomLayout)
    }
}
