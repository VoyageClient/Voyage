/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.MovementMethod
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.MarginLayoutParamsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.TextViewCompat
import com.airbnb.epoxy.EpoxyAttribute
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.core.extensions.setSenderNameEmphasis
import im.vector.app.core.platform.ProfileColorFadeRegistry
import im.vector.app.core.ui.views.SendStateImageView
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.MessageColorProvider
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.reply.InReplyToView
import im.vector.app.features.home.room.detail.timeline.reply.PreviewReplyUiState
import im.vector.app.features.home.room.detail.timeline.reply.ReplyPreviewRetriever
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayout
import im.vector.app.features.home.room.detail.timeline.tools.LinkClickSourceHolder
import im.vector.app.features.home.room.detail.timeline.tools.bindSenderNameFades
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.home.room.detail.timeline.view.ScMessageBubbleWrapView
import im.vector.app.features.home.room.detail.timeline.view.canHideAvatars
import im.vector.app.features.home.room.detail.timeline.view.infoInBubbles
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.permalinks.PermalinkService
import org.matrix.android.sdk.api.session.threads.ThreadDetails
import org.matrix.android.sdk.api.util.MatrixItem
import kotlin.math.ceil

private const val FORWARDED_ICON_SCALE = 1.15f
private const val FORWARDED_ICON_PADDING_DP = 4

/**
 * Base timeline item that adds an optional information bar with the sender avatar, name, time, send state.
 * Adds associated click listeners (on avatar, displayname).
 */
abstract class AbsMessageItem<H : AbsMessageItem.Holder>(
        @LayoutRes layoutId: Int = R.layout.item_timeline_event_base
) : AbsBaseMessageItem<H>(layoutId) {

    override val baseAttributes: AbsBaseMessageItem.Attributes
        get() = attributes

    override fun isCacheable(): Boolean {
        return attributes.informationData.sendStateDecoration != SendStateDecoration.SENT
    }

    @EpoxyAttribute
    lateinit var attributes: Attributes

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var movementMethod: MovementMethod? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var replyPreviewRetriever: ReplyPreviewRetriever? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var inReplyToClickCallback: TimelineEventController.InReplyToClickCallback? = null

    private val replyViewUpdater = ReplyViewUpdater()

    private val _avatarClickListener = object : ClickListener {
        override fun invoke(p1: View) {
            attributes.avatarCallback?.onAvatarClicked(attributes.informationData)
        }
    }

    private val _threadClickListener = object : ClickListener {
        override fun invoke(p1: View) {
            attributes.threadCallback?.onThreadSummaryClicked(attributes.informationData.eventId, attributes.threadDetails?.isRootThread ?: false)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun bind(holder: H) {
        super.bind(holder)
        holder.boundStableId = attributes.informationData.stableId

        val scBindMarker = im.vector.app.core.utils.PerfTrace.mark("bind.super.scCustomBind")
        val scBound = (holder.view as? ScMessageBubbleWrapView)?.customBind(this, holder, attributes, _avatarClickListener) == true
        scBindMarker.end()
        if (!scBound) {
        // Indentation kept flat for easy diffing against upstream

        if (attributes.informationData.messageLayout.showAvatar) {
            holder.avatarImageView.layoutParams = holder.avatarImageView.layoutParams?.apply {
                height = attributes.avatarSize
                width = attributes.avatarSize
            }
            im.vector.app.core.utils.PerfTrace.time("bind.super.avatar") {
                // The size the row was just given, rather than whatever the view has been measured to:
                // the layoutParams above have not been through a layout pass yet.
                attributes.avatarRenderer.renderAtSize(
                        attributes.informationData.matrixItem, holder.avatarImageView, attributes.avatarSize
                )
                // Tapping the name/avatar mentions the sender, so have the pill's avatar ready by then.
                attributes.avatarRenderer.preloadAvatar(attributes.informationData.matrixItem, holder.avatarImageView)
            }
            holder.avatarImageView.setOnLongClickListener(attributes.itemLongClickListener)
            holder.avatarImageView.isVisible = true
            holder.avatarImageView.onClick(_avatarClickListener)
        } else {
            holder.avatarImageView.setOnClickListener(null)
            holder.avatarImageView.setOnLongClickListener(null)
            holder.avatarImageView.isVisible = false
        }
        if (attributes.informationData.messageLayout.showDisplayName) {
            holder.memberNameView.isVisible = true
            holder.memberNameView.text = attributes.informationData.memberName?.prepareForDisplay()
            val memberNameColor = attributes.getMemberNameColor()
            ProfileColorFadeRegistry.setTimelineColor(
                    holder.memberNameView,
                    attributes.informationData.senderId,
                    memberNameColor,
                    attributes.messageColorProvider.shouldFadeProfile(attributes.informationData.matrixItem),
            )
            attributes.messageColorProvider.profileFadeBound(attributes.informationData.matrixItem)
            holder.memberNameView.setSenderNameEmphasis(attributes.isMemberNameColored())
            holder.memberNameView.onClick(attributes.memberClickListener)
            holder.memberNameView.setOnLongClickListener(attributes.itemLongClickListener)
        } else {
            holder.memberNameView.setOnClickListener(null)
            holder.memberNameView.setOnLongClickListener(null)
            holder.memberNameView.isVisible = false
        }
        if (attributes.informationData.messageLayout.showTimestamp) {
            holder.timeView.isVisible = true
            holder.timeView.text = attributes.informationData.time
        } else {
            holder.timeView.isVisible = false
        }

        // Render send state indicator
        holder.sendStateImageView.render(attributes.informationData.sendStateDecoration)
        holder.eventSendingIndicator.isVisible = attributes.informationData.sendStateDecoration == SendStateDecoration.SENDING_MEDIA

        // Indentation kept flat for easy diffing against upstream - end
        }

        bindForwardedFrame(holder)

        // Threads
        val threadsMarker = im.vector.app.core.utils.PerfTrace.mark("bind.super.threads")
        if (attributes.areThreadMessagesEnabled) {
            holder.threadSummaryConstraintLayout.onClick(_threadClickListener)
            attributes.threadDetails?.let { threadDetails ->
                holder.threadSummaryConstraintLayout.isVisible = threadDetails.isRootThread
                holder.threadSummaryCounterTextView.text = "${threadDetails.numberOfThreads}"
                holder.threadSummaryInfoTextView.text = (attributes.threadSummaryFormatted ?: attributes.decryptionErrorMessage)?.prepareForDisplay()
                // Colour only: this one-line chip already sits behind a counter and avatar, so the trash icon would crowd it.
                holder.threadSummaryInfoTextView.setTextColor(
                        ThemeUtils.getColor(
                                holder.view.context,
                                if (attributes.threadSummaryRedacted) {
                                    im.vector.lib.ui.styles.R.attr.vctr_content_tertiary
                                } else {
                                    im.vector.lib.ui.styles.R.attr.vctr_content_secondary
                                }
                        )
                )

                val userId = threadDetails.threadSummarySenderInfo?.userId ?: return@let
                val displayName = threadDetails.threadSummarySenderInfo?.displayName
                val avatarUrl = threadDetails.threadSummarySenderInfo?.avatarUrl.takeUnless { attributes.informationData.hideAvatars }
                attributes.avatarRenderer.render(MatrixItem.UserItem(userId, displayName, avatarUrl), holder.threadSummaryAvatarImageView)
                updateHighlightedMessageHeight(holder, true)
            } ?: run {
                holder.threadSummaryConstraintLayout.isVisible = false
                updateHighlightedMessageHeight(holder, false)
            }
        }

        threadsMarker.end()

        // Replies (SchildiChat-style preview rendered in all layouts)
        val replyMarker = im.vector.app.core.utils.PerfTrace.mark("bind.super.reply")
        if (holder.replyToView != null) {
            replyViewUpdater.replyView = holder.replyToView
            holder.replyToView?.delegate = inReplyToClickCallback
            // Stamp the source ids before addListener (which renders the current state synchronously):
            // ReplyViewUpdater matches on sourceStableId to drop stale async updates onto a reused view,
            // while navigation needs the real event id (a transaction id resolves to nothing).
            holder.replyToView?.sourceEventId = attributes.informationData.eventId
            holder.replyToView?.sourceStableId = attributes.informationData.stableId
            holder.replyToView?.sourceIsSent = attributes.informationData.sendState.isSent()
            val safeReplyPreviewRetriever = replyPreviewRetriever
            if (safeReplyPreviewRetriever == null) {
                holder.replyToView?.isVisible = false
            } else {
                safeReplyPreviewRetriever.addListener(attributes.informationData.stableId, replyViewUpdater)
            }
            bindReplyBand(holder)
        }
        replyMarker.end()
    }

    // A forward is framed by a bar down the start edge beside a "Forwarded" header, the message, and a
    // footer naming where and when it was first sent.
    // The touch listener only records where the tap landed; performClick still runs the click listener.
    @SuppressLint("ClickableViewAccessibility")
    private fun bindForwardedFrame(holder: Holder) {
        val header = holder.forwardedNoticeView ?: return
        val footer = holder.forwardedFooterView
        val bar = holder.forwardedBar
        val forwardedInfo = attributes.informationData.forwardedInfo
        listOfNotNull(header, footer).forEach {
            it.setOnClickListener(null)
            it.setOnLongClickListener(null)
            it.isClickable = false
        }
        header.setOnTouchListener(null)
        val isBubble = attributes.informationData.messageLayout is TimelineMessageLayout.ScBubble
        val res = header.resources
        val indent = if (forwardedInfo == null) {
            0
        } else if (isBubble) {
            res.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.forwarded_bubble_text_inset)
        } else {
            // The content container brings its own start padding.
            res.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.forwarded_text_inset) - holder.viewStubContainer.paddingLeft
        }
        holder.viewStubContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            if (MarginLayoutParamsCompat.getMarginStart(this) != indent) {
                MarginLayoutParamsCompat.setMarginStart(this, indent)
            }
        }
        if (!isBubble) {
            // The send state belongs at the end of the whole forward, not of the quoted content.
            val anchor = if (forwardedInfo != null && footer != null) R.id.messageForwardedFooterView else R.id.viewStubContainer
            listOf(holder.sendStateImageView, holder.eventSendingIndicator).forEach { indicator ->
                indicator.updateLayoutParams<RelativeLayout.LayoutParams> { addRule(RelativeLayout.ALIGN_BOTTOM, anchor) }
            }
        }
        if (forwardedInfo == null) {
            header.isVisible = false
            footer?.isVisible = false
            bar?.isVisible = false
            return
        }
        val context = header.context
        val sender = forwardedInfo.senderMatrixItem
        header.isVisible = true
        val senderRange = if (sender != null) {
            val name = (sender.displayName ?: sender.id).prepareForDisplay()
            val text = context.getString(CommonStrings.forwarded_message_from_user, name).prepareForDisplay()
            val start = text.indexOf(name.toString())
            header.text = SpannableStringBuilder(text).apply {
                if (start >= 0) setSpan(attributes.messageColorProvider.senderNameSpan(sender), start, start + name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            header.bindSenderNameFades()
            if (start >= 0) start until start + name.length else null
        } else {
            header.text = context.getString(CommonStrings.forwarded_message)
            null
        }
        bindForwardedIcon(header)

        val roomName = forwardedInfo.roomName?.prepareForDisplay() ?: context.getString(CommonStrings.forwarded_unknown_room)
        footer?.isVisible = true
        footer?.text = listOfNotNull(roomName, forwardedInfo.formattedDate)
                .joinToString(context.getString(CommonStrings.forwarded_footer_separator))

        if (bar != null) {
            bar.isVisible = true
            if (bar.background == null) {
                bar.backgroundCompat = GradientDrawable().apply {
                    setColor(ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_quaternary))
                    cornerRadius = res.getDimension(im.vector.lib.ui.styles.R.dimen.forwarded_bar_width) / 2
                }
            }
            bar.updateLayoutParams<RelativeLayout.LayoutParams> {
                addRule(RelativeLayout.ALIGN_BOTTOM, if (footer != null) R.id.messageForwardedFooterView else R.id.viewStubContainer)
            }
        }

        if (isBubble) {
            // The header and footer are clamped to the content column; widen it rather than cut them short.
            val needed = listOfNotNull(header, footer).maxOf { it.naturalWidth() }
            holder.viewStubContainer.minimumWidth = maxOf(ViewCompat.getMinimumWidth(holder.viewStubContainer), needed)
        }

        val permalink = forwardedInfo.permalink
        val openOriginal = permalink?.let {
            View.OnClickListener {
                // Same-room jumps offer a way back to this message, as body permalinks do.
                LinkClickSourceHolder.record(attributes.informationData.eventId)
                attributes.callback?.onUrlClicked(permalink, header.text.toString())
            }
        }
        footer?.setOnClickListener(openOriginal)
        if (openOriginal != null) footer?.setOnLongClickListener(attributes.itemLongClickListener)

        if (sender != null && senderRange != null) {
            // A ClickableSpan would fire the header's own click too, so the tap position decides instead.
            header.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) holder.lastForwardHeaderTouch = event.x to event.y
                false
            }
            header.setOnClickListener {
                val (x, y) = holder.lastForwardHeaderTouch ?: (-1f to -1f)
                if (header.getOffsetForPosition(x, y) in senderRange) {
                    attributes.callback?.onUrlClicked(PermalinkService.MATRIX_TO_URL_BASE + sender.id, sender.id)
                } else {
                    openOriginal?.onClick(header)
                }
            }
            header.setOnLongClickListener(attributes.itemLongClickListener)
        } else {
            header.setOnClickListener(openOriginal)
            if (openOriginal != null) header.setOnLongClickListener(attributes.itemLongClickListener)
        }
    }

    private fun bindForwardedIcon(header: TextView) {
        val icon = DrawableCompat.wrap(AppCompatResources.getDrawable(header.context, R.drawable.ic_forward)?.mutate() ?: return)
        DrawableCompat.setTint(icon, header.currentTextColor)
        val size = (header.textSize * FORWARDED_ICON_SCALE).toInt()
        icon.setBounds(0, 0, size, size)
        TextViewCompat.setCompoundDrawablesRelative(header, icon, null, null, null)
        header.compoundDrawablePadding = (header.resources.displayMetrics.density * FORWARDED_ICON_PADDING_DP).toInt()
    }

    private fun TextView.naturalWidth(): Int {
        val icon = TextViewCompat.getCompoundDrawablesRelative(this)[0]?.bounds?.width()?.plus(compoundDrawablePadding) ?: 0
        return ceil(paint.measureText(text.toString())).toInt() + icon + paddingLeft + paddingRight
    }

    /**
     * Bands the row across the reply header's vertical extent when the quoted event is showing content
     * recovered from a redaction.
     *
     * The band can't live on the reply header itself: that view sits inside the content column, so its
     * background stops short of the avatar gutter and the timestamp column. It is painted on the row-level
     * tint drawable instead, which means its extent has to be read back off the header — and the header's
     * content resolves asynchronously and changes height when it does, so one measurement at bind time is
     * not enough. Hence the pre-draw pass (correct geometry for this frame, whatever bind saw), the layout
     * listener (later content arriving), and the header's own callback for a reveal that swaps content of
     * the same height, which fires no layout pass of its own.
     */
    private fun bindReplyBand(holder: Holder) {
        val reply = holder.replyToView ?: return
        if (holder.replyBandListener == null) {
            val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateReplyBand(holder) }
            holder.replyBandListener = listener
            reply.addOnLayoutChangeListener(listener)
        }
        reply.onBandStateChanged = { updateReplyBand(holder) }
        holder.view.doOnPreDraw { updateReplyBand(holder) }
        updateReplyBand(holder)
    }

    private fun updateReplyBand(holder: Holder) {
        val reply = holder.replyToView
        if (reply == null || !reply.isVisible || !reply.quotesRevealedRedaction || reply.height <= 0) {
            holder.redactionTint.clearBand()
            return
        }
        // Relative to the tint view, which isn't flush with the row's top in every layout.
        val top = reply.topRelativeTo(holder.view) - holder.rowTint.top
        holder.redactionTint.setBand(
                color = ThemeUtils.getColor(holder.view.context, im.vector.lib.ui.styles.R.attr.vctr_redacted_background),
                top = top,
                bottom = top + reply.height,
        )
    }

    private fun View.topRelativeTo(root: View): Int {
        var offset = 0
        var current: View = this
        while (current !== root) {
            offset += current.top
            current = current.parent as? View ?: break
        }
        return offset
    }

    private fun updateHighlightedMessageHeight(holder: Holder, isExpanded: Boolean) {
        holder.checkableBackground.updateLayoutParams<RelativeLayout.LayoutParams> {
            if (isExpanded) {
                addRule(RelativeLayout.ALIGN_BOTTOM, holder.threadSummaryConstraintLayout.id)
            } else {
                addRule(RelativeLayout.ALIGN_BOTTOM, holder.informationBottom.id)
            }
        }
    }

    override fun ignoreMessageGuideline(): Boolean {
        val messageLayout = attributes.informationData.messageLayout as? TimelineMessageLayout.ScBubble ?: return false
        return infoInBubbles(messageLayout) && canHideAvatars(attributes)
    }

    override fun unbind(holder: H) {
        attributes.avatarRenderer.clear(holder.avatarImageView)
        holder.avatarImageView.setOnClickListener(null)
        holder.avatarImageView.setOnLongClickListener(null)
        holder.memberNameView.setOnClickListener(null)
        holder.memberNameView.setOnLongClickListener(null)
        attributes.avatarRenderer.clear(holder.threadSummaryAvatarImageView)
        holder.threadSummaryConstraintLayout.setOnClickListener(null)
        replyPreviewRetriever?.removeListener(attributes.informationData.stableId, replyViewUpdater)
        replyViewUpdater.replyView = null
        holder.replyBandListener?.let { holder.replyToView?.removeOnLayoutChangeListener(it) }
        holder.replyBandListener = null
        holder.replyToView?.onBandStateChanged = null
        holder.forwardedNoticeView?.setOnClickListener(null)
        holder.forwardedNoticeView?.setOnLongClickListener(null)
        holder.forwardedNoticeView?.setOnTouchListener(null)
        holder.forwardedFooterView?.setOnClickListener(null)
        holder.forwardedFooterView?.setOnLongClickListener(null)
        holder.redactionTint.clearBand()
        super.unbind(holder)
    }

    override fun getInformationData(): MessageInformationData? = attributes.informationData

    inner class ReplyViewUpdater : ReplyPreviewRetriever.PreviewReplyRetrieverListener {
        var replyView: InReplyToView? = null

        override fun onStateUpdated(state: PreviewReplyUiState, force: Boolean) {
            val view = replyView ?: return
            // A rebind (notifyItemChanged) doesn't unbind the previous model, so its updater stays
            // registered with a replyView that RecyclerView may later reuse for another message. Ignore
            // the update unless the view still hosts this event, else one reply flashes another's preview.
            if (view.sourceStableId != attributes.informationData.stableId) return
            replyPreviewRetriever?.let {
                view.render(
                        state,
                        it,
                        attributes.informationData,
                        attributes.itemLongClickListener,
                        force,
                )
            }
        }
    }

    abstract class Holder(@IdRes stubId: Int) : AbsBaseMessageItem.Holder(stubId) {

        val avatarImageView by bind<ImageView>(R.id.messageAvatarImageView)
        val memberNameView by bind<TextView>(R.id.messageMemberNameView)
        val timeView by bind<TextView>(R.id.messageTimeView)
        val sendStateImageView by bind<SendStateImageView>(R.id.messageSendStateImageView)
        val eventSendingIndicator by bind<ProgressBar>(R.id.eventSendingIndicator)
        val replyToView: InReplyToView? by lazy { view.findViewById(R.id.inReplyToContainer) }
        val forwardedNoticeView: TextView? by lazy { view.findViewById(R.id.messageForwardedNoticeView) }
        val forwardedFooterView: TextView? by lazy { view.findViewById(R.id.messageForwardedFooterView) }
        val forwardedBar: View? by lazy { view.findViewById(R.id.messageForwardedBar) }
        var lastForwardHeaderTouch: Pair<Float, Float>? = null
        var replyBandListener: View.OnLayoutChangeListener? = null

        // The message this holder's views currently show, for guarding async work posted by an earlier bind.
        var boundStableId: String? = null
        val informationBottom by bind<LinearLayout>(R.id.informationBottom)
        val threadSummaryConstraintLayout by bind<ConstraintLayout>(R.id.messageThreadSummaryConstraintLayout)
        val threadSummaryCounterTextView by bind<TextView>(R.id.messageThreadSummaryCounterTextView)
        val threadSummaryAvatarImageView by bind<ImageView>(R.id.messageThreadSummaryAvatarImageView)
        val threadSummaryInfoTextView by bind<TextView>(R.id.messageThreadSummaryInfoTextView)
    }

    /**
     * This class holds all the common attributes for timeline items.
     */
    data class Attributes(
            val avatarSize: Int,
            override val informationData: MessageInformationData,
            override val avatarRenderer: AvatarRenderer,
            override val messageColorProvider: MessageColorProvider,
            override val itemLongClickListener: View.OnLongClickListener? = null,
            override val itemClickListener: ClickListener? = null,
            val memberClickListener: ClickListener? = null,
            val callback: TimelineEventController.Callback? = null,
            override val reactionPillCallback: TimelineEventController.ReactionPillCallback? = null,
            val avatarCallback: TimelineEventController.AvatarCallback? = null,
            val threadCallback: TimelineEventController.ThreadCallback? = null,
            override val readReceiptsCallback: TimelineEventController.ReadReceiptsCallback? = null,
            val emojiTypeFace: Typeface? = null,
            val decryptionErrorMessage: String? = null,
            val threadSummaryFormatted: CharSequence? = null,
            val threadSummaryRedacted: Boolean = false,
            val threadDetails: ThreadDetails? = null,
            val areThreadMessagesEnabled: Boolean = false,
            val autoplayAnimatedImages: Boolean = false,
            override val reactionsSummaryEvents: ReactionsSummaryEvents? = null,
    ) : AbsBaseMessageItem.Attributes {

        fun getMemberNameColor() = messageColorProvider.getMemberNameTextColor(informationData.matrixItem)

        fun isMemberNameColored() = messageColorProvider.isMemberNameColored()

        // Have to override as it's used to diff epoxy items
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Attributes

            if (avatarSize != other.avatarSize) return false
            if (informationData != other.informationData) return false
            if (threadDetails != other.threadDetails) return false

            return true
        }

        override fun hashCode(): Int {
            var result = avatarSize
            result = 31 * result + informationData.hashCode()
            result = 31 * result + threadDetails.hashCode()

            return result
        }
    }
}
