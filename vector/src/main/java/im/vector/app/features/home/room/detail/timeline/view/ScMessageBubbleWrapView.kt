/*
 * Copyright 2021-2024 SchildiChat and New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.core.extensions.layoutDirectionCompat
import im.vector.app.core.extensions.marginEndCompat
import im.vector.app.core.extensions.marginStartCompat
import im.vector.app.core.extensions.removeRuleCompat
import im.vector.app.core.extensions.setPaddingRelativeCompat
import im.vector.app.core.resources.DefaultLocaleProvider
import im.vector.app.core.resources.getLayoutDirectionFromCurrentLocale
import im.vector.app.core.ui.views.BubbleDependentView
import im.vector.app.core.ui.views.SelectionAwareRelativeLayout
import im.vector.app.databinding.ViewMessageBubbleScBinding
import im.vector.app.features.home.room.detail.timeline.item.AbsMessageItem
import im.vector.app.features.home.room.detail.timeline.item.AnonymousReadReceipt
import im.vector.app.features.home.room.detail.timeline.item.BaseEventItem
import im.vector.app.features.home.room.detail.timeline.item.E2EDecoration
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.home.room.detail.timeline.item.SendStateDecoration
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayout
import im.vector.app.features.themes.BubbleThemeUtils
import im.vector.app.features.themes.BubbleThemeUtils.Companion.BUBBLE_TIME_BOTTOM
import im.vector.app.features.themes.BubbleThemeUtils.Companion.BUBBLE_TIME_TOP
import im.vector.app.features.themes.ThemeUtils
import im.vector.app.features.themes.guessTextWidth
import timber.log.Timber
import kotlin.math.ceil
import kotlin.math.max

class ScMessageBubbleWrapView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
) : SelectionAwareRelativeLayout(context, attrs, defStyleAttr), TimelineMessageLayoutRenderer {

    companion object {
        private const val OUTGOING_TINT_RATIO = 0.20f
    }

    private var isIncoming: Boolean = false

    private lateinit var views: ViewMessageBubbleScBinding

    private val localeProvider = DefaultLocaleProvider(resources)

    init {
        inflate(context, R.layout.view_message_bubble_sc, this)
        context.withStyledAttributes(attrs, im.vector.lib.ui.styles.R.styleable.MessageBubble) {
            isIncoming = getBoolean(im.vector.lib.ui.styles.R.styleable.MessageBubble_incoming_style, false)
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        views = ViewMessageBubbleScBinding.bind(this)
    }

    // Element's native renderer entry point - unused for SC bubbles
    override fun renderMessageLayout(messageLayout: TimelineMessageLayout) {}

    fun <H : VectorEpoxyHolder> customBind(
            bubbleDependentView: BubbleDependentView<H>,
            holder: H,
            attributes: AbsMessageItem.Attributes,
            _avatarClickListener: ClickListener,
    ): Boolean {
        if (attributes.informationData.messageLayout !is TimelineMessageLayout.ScBubble) {
            Timber.v("Can't render messageLayout ${attributes.informationData.messageLayout}")
            return false
        }

        val contentInBubble = infoInBubbles(attributes.informationData.messageLayout)
        val senderInBubble = senderNameInBubble(attributes.informationData.messageLayout)

        val avatarImageView: ImageView?
        var memberNameView: TextView?
        var timeView: TextView?
        val hiddenViews = ArrayList<View>()
        val invisibleViews = ArrayList<View>()

        val canHideAvatar = canHideAvatars(attributes)
        val canHideSender = canHideSender(attributes)

        // Select which views are visible, based on bubble style and other criteria
        if (attributes.informationData.messageLayout.showDisplayName) {
            if (senderInBubble) {
                memberNameView = views.bubbleMessageMemberNameView
                hiddenViews.add(views.messageMemberNameView)
            } else {
                memberNameView = views.messageMemberNameView
                hiddenViews.add(views.bubbleMessageMemberNameView)
            }
            if (contentInBubble) {
                timeView = views.bubbleMessageTimeView
                hiddenViews.add(views.messageTimeView)
            } else {
                timeView = views.messageTimeView
                hiddenViews.add(views.bubbleMessageTimeView)
            }
        } else if (attributes.informationData.messageLayout.showTimestamp) {
            memberNameView = null
            hiddenViews.add(views.bubbleMessageMemberNameView)
            if (contentInBubble) {
                timeView = views.bubbleMessageTimeView
                hiddenViews.add(views.messageTimeView)

                hiddenViews.add(views.messageMemberNameView)
            } else {
                timeView = views.messageTimeView
                hiddenViews.add(views.bubbleMessageTimeView)

                invisibleViews.add(views.messageMemberNameView)
            }
        } else {
            memberNameView = null
            hiddenViews.add(views.messageMemberNameView)
            hiddenViews.add(views.bubbleMessageMemberNameView)
            timeView = null
            hiddenViews.add(views.messageTimeView)
            hiddenViews.add(views.bubbleMessageTimeView)
        }

        if (timeView === views.bubbleMessageTimeView) {
            // We have two possible bubble time view locations
            if (getBubbleTimeLocation(attributes.informationData.messageLayout) == BubbleThemeUtils.BUBBLE_TIME_BOTTOM) {
                timeView = views.bubbleFooterMessageTimeView
                if (attributes.informationData.messageLayout.showDisplayName) {
                    if (canHideSender) {
                        // In the case of footer time, we can also hide the names without making it look awkward
                        if (memberNameView != null) {
                            hiddenViews.add(memberNameView)
                            memberNameView = null
                        }
                        hiddenViews.add(views.bubbleMessageTimeView)
                    } else if (!senderInBubble) {
                        // We don't need to reserve space here
                        hiddenViews.add(views.bubbleMessageTimeView)
                    } else {
                        // Don't completely remove, just hide, so our relative layout rules still work
                        invisibleViews.add(views.bubbleMessageTimeView)
                    }
                } else {
                    // Do hide, or we accidentally reserve space
                    hiddenViews.add(views.bubbleMessageTimeView)
                }
            } else {
                hiddenViews.add(views.bubbleFooterMessageTimeView)
            }
        }

        // While media is still uploading, hide the overlaid timestamp: it can't anchor to the
        // not-yet-laid-out image (so it lands in the wrong spot) and the upload progress bar already
        // shows status. It reappears on the next bind once the send completes.
        if (attributes.informationData.sendState.isSending() &&
                bubbleDependentView.allowFooterOverlay(holder, this) &&
                !bubbleDependentView.needsFooterReservation()) {
            hiddenViews.add(views.bubbleFootView)
            timeView?.let { hiddenViews.add(it) }
            timeView = null
        }

        // Dual-side bubbles: hide own avatar, and all in direct chats
        if ((!attributes.informationData.messageLayout.showAvatar) ||
                (contentInBubble && canHideAvatar)) {
            avatarImageView = null
            hiddenViews.add(views.messageAvatarImageView)
        } else {
            avatarImageView = views.messageAvatarImageView
        }

        // Views available in upstream Element
        avatarImageView?.layoutParams = avatarImageView?.layoutParams?.apply {
            height = attributes.avatarSize
            width = attributes.avatarSize
        }
        avatarImageView?.visibility = View.VISIBLE
        avatarImageView?.onClick(_avatarClickListener)
        memberNameView?.visibility = View.VISIBLE
        memberNameView?.onClick(attributes.memberClickListener)
        timeView?.visibility = View.VISIBLE
        timeView?.text = attributes.informationData.time
        memberNameView?.text = attributes.informationData.memberName?.prepareForDisplay()
        memberNameView?.setTextColor(attributes.getMemberNameColor())
        if (avatarImageView != null) attributes.avatarRenderer.render(attributes.informationData.matrixItem, avatarImageView)
        avatarImageView?.setOnLongClickListener(attributes.itemLongClickListener)
        memberNameView?.setOnLongClickListener(attributes.itemLongClickListener)

        // More extra views added by Schildi
        if (senderInBubble) {
            views.viewStubContainer.root.minimumWidth = getViewStubMinimumWidth(bubbleDependentView, holder, attributes, contentInBubble, canHideSender)
        } else {
            views.viewStubContainer.root.minimumWidth = 0
        }
        if (contentInBubble) {
            views.bubbleFootView.visibility = View.VISIBLE
        } else {
            hiddenViews.add(views.bubbleFootView)
        }

        // Actually hide all unnecessary views
        hiddenViews.forEach {
            it.visibility = View.GONE
        }
        invisibleViews.forEach {
            it.visibility = View.INVISIBLE
        }
        // Render send state indicator
        if (contentInBubble) {
            // Bubbles have their own decoration in the anonymous read receipt (in the message footer)
            views.messageSendStateImageView.isVisible = false
            views.eventSendingIndicator.isVisible = false
        } else {
            views.messageSendStateImageView.render(attributes.informationData.sendStateDecoration)
            views.eventSendingIndicator.isVisible = attributes.informationData.sendStateDecoration == SendStateDecoration.SENDING_MEDIA
        }

        if (attributes.informationData.messageLayout.showsE2eDecorationInFooter()) {
            if (attributes.informationData.isPgp) {
                views.bubbleFooterMessageE2EDecoration.renderPgpLock()
            } else {
                views.bubbleFooterMessageE2EDecoration.renderE2EDecoration(attributes.informationData.e2eDecoration)
            }
        }

        return true
    }

    override fun <H : VectorEpoxyHolder> renderBaseMessageLayout(
            messageLayout: TimelineMessageLayout,
            bubbleDependentView: BubbleDependentView<H>,
            holder: H,
    ) {
        if (messageLayout !is TimelineMessageLayout.ScBubble) {
            Timber.v("Can't render messageLayout $messageLayout")
            return
        }

        bubbleDependentView.applyScBubbleStyle(messageLayout, holder)

        renderStubMessageLayout(messageLayout, views.viewStubContainer.root)

        // Padding for views that align with the bubble (should be roughly the bubble tail width)
        val bubbleStartAlignWidth = views.informationBottom.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_bubble_tail_size)
        if (messageLayout.reverseBubble) {
            // Align reactions container to bubble
            views.informationBottom.setPaddingRelativeCompat(0, 0, bubbleStartAlignWidth, 0)
        } else {
            // Align reactions container to bubble
            views.informationBottom.setPaddingRelativeCompat(bubbleStartAlignWidth, 0, 0, 0)
        }
    }

    override fun <H : BaseEventItem.BaseHolder> renderMessageLayout(
            messageLayout: TimelineMessageLayout,
            bubbleDependentView: BubbleDependentView<H>,
            holder: H,
    ) {
        if (messageLayout !is TimelineMessageLayout.ScBubble) {
            Timber.v("Can't render messageLayout $messageLayout")
            return
        }

        renderBaseMessageLayout(messageLayout, bubbleDependentView, holder)

        val bubbleView = views.bubbleView
        val informationData = bubbleDependentView.getInformationData()
        val contentInBubble = infoInBubbles(messageLayout)

        val defaultDirection = localeProvider.getLayoutDirectionFromCurrentLocale()
        val defaultRtl = defaultDirection == View.LAYOUT_DIRECTION_RTL
        val reverseDirection = if (defaultRtl) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL

        // Notice formatting - also relevant if no actual bubbles are shown
        bubbleView.alpha = if (messageLayout.isNotice) 0.65f else 1f

        if (messageLayout.isRealBubble || messageLayout.isPseudoBubble) {
            // Padding for bubble content: long for side with tail, short for other sides
            val longPadding: Int
            val shortPadding: Int
            // Element/SchildiChat keep outgoing bubbles neutral; tint them with the accent when enabled.
            val hasTail = !messageLayout.isPseudoBubble && messageLayout.showAvatar
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP && hasTail) {
                // Pre-21 the XML layer-list can't size the tail (item gravity/size is API 23+), so draw
                // the bubble (body + tail) ourselves to keep the tail the right height.
                val base = ThemeUtils.getColor(
                        bubbleView.context,
                        if (messageLayout.isIncoming) im.vector.lib.ui.styles.R.attr.sc_message_bg_incoming
                        else im.vector.lib.ui.styles.R.attr.sc_message_bg_outgoing
                )
                val color = if (!messageLayout.isIncoming && messageLayout.tintOutgoing) {
                    val accent = ThemeUtils.getColor(bubbleView.context, com.google.android.material.R.attr.colorAccent)
                    ColorUtils.blendARGB(base, accent, OUTGOING_TINT_RATIO)
                } else {
                    base
                }
                bubbleView.backgroundCompat =ScBubbleBackgroundDrawable(
                        fillColor = color,
                        cornerRadius = messageLayout.bubbleAppearance.getBubbleRadiusPx(bubbleView.context).toFloat(),
                        tailWidth = bubbleView.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_bubble_tail_size).toFloat(),
                        tailHeight = bubbleView.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_bubble_tail_height).toFloat(),
                        tailOnRight = messageLayout.reverseBubble != defaultRtl,
                )
            } else {
                bubbleView.setBackgroundResource(messageLayout.bubbleDrawable)
                if (!messageLayout.isPseudoBubble && !messageLayout.isIncoming && messageLayout.tintOutgoing) {
                    val accent = ThemeUtils.getColor(bubbleView.context, com.google.android.material.R.attr.colorAccent)
                    val base = ThemeUtils.getColor(bubbleView.context, im.vector.lib.ui.styles.R.attr.sc_message_bg_outgoing)
                    // PorterDuffColorFilter (API 1) instead of DrawableCompat.setTint, which is a no-op on
                    // an unwrapped LayerDrawable pre-21.
                    bubbleView.background?.mutate()?.colorFilter =
                            PorterDuffColorFilter(ColorUtils.blendARGB(base, accent, OUTGOING_TINT_RATIO), PorterDuff.Mode.SRC_IN)
                }
            }
            if (!messageLayout.isPseudoBubble) {
                longPadding = bubbleView.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_bubble_inner_padding_long_side)
                shortPadding = bubbleView.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_bubble_inner_padding_short_side)
            } else {
                longPadding = bubbleView.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_bubble_tail_size)
                shortPadding = 0
            }
            if (messageLayout.reverseBubble != defaultRtl) {
                // Use left/right instead of start/end: bubbleView is always LTR
                (bubbleView.layoutParams as ViewGroup.MarginLayoutParams).leftMargin = bubbleDependentView.getScBubbleMargin(bubbleView.resources)
                (bubbleView.layoutParams as ViewGroup.MarginLayoutParams).rightMargin = 0
            } else {
                (bubbleView.layoutParams as ViewGroup.MarginLayoutParams).leftMargin = 0
                (bubbleView.layoutParams as ViewGroup.MarginLayoutParams).rightMargin = bubbleDependentView.getScBubbleMargin(bubbleView.resources)
            }
            if (messageLayout.reverseBubble != defaultRtl) {
                bubbleView.setPadding(shortPadding, shortPadding, longPadding, shortPadding)
            } else {
                bubbleView.setPadding(longPadding, shortPadding, shortPadding, shortPadding)
            }

            if (contentInBubble) {
                val anonymousReadReceipt = BubbleThemeUtils.getVisibleAnonymousReadReceipts(
                        informationData?.readReceiptAnonymous, !messageLayout.isIncoming)

                when (anonymousReadReceipt) {
                    AnonymousReadReceipt.PROCESSING -> {
                        views.bubbleFooterReadReceipt.visibility = View.VISIBLE
                        views.bubbleFooterReadReceipt.setImageResource(R.drawable.ic_processing_msg)
                    }
                    else -> {
                        views.bubbleFooterReadReceipt.visibility = View.GONE
                    }
                }

                // We can't use end and start because of our weird layout RTL tricks
                val alignEnd = if (defaultRtl) RelativeLayout.ALIGN_LEFT else RelativeLayout.ALIGN_RIGHT
                val alignStart = if (defaultRtl) RelativeLayout.ALIGN_RIGHT else RelativeLayout.ALIGN_LEFT
                val startOf = if (defaultRtl) RelativeLayout.RIGHT_OF else RelativeLayout.LEFT_OF
                val endOf = if (defaultRtl) RelativeLayout.LEFT_OF else RelativeLayout.RIGHT_OF

                val footerLayoutParams = views.bubbleFootView.layoutParams as RelativeLayout.LayoutParams
                var footerMarginStartDp = views.bubbleFootView.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_footer_margin_start)
                var footerMarginEndDp = views.bubbleFootView.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_footer_margin_end)
                if (bubbleDependentView.allowFooterOverlay(holder, this)) {
                    footerLayoutParams.addRule(RelativeLayout.ALIGN_BOTTOM, R.id.viewStubContainer)
                    footerLayoutParams.addRule(alignEnd, R.id.viewStubContainer)
                    footerLayoutParams.removeRuleCompat(alignStart)
                    footerLayoutParams.removeRuleCompat(RelativeLayout.BELOW)
                    footerLayoutParams.removeRuleCompat(endOf)
                    footerLayoutParams.removeRuleCompat(startOf)
                    if (bubbleDependentView.needsFooterReservation()) {
                        // Remove style used when not having reserved space
                        removeFooterOverlayStyle()

                        // Calculate required footer space
                        val footerMeasures = getFooterMeasures(informationData, anonymousReadReceipt)
                        val footerWidth = footerMeasures[0]
                        val footerHeight = footerMeasures[1]

                        bubbleDependentView.reserveFooterSpace(holder, footerWidth, footerHeight)
                    } else {
                        // We have no reserved space -> style it to ensure readability on arbitrary backgrounds
                        styleFooterOverlay(messageLayout)
                    }
                } else {
                    when {
                        bubbleDependentView.allowFooterBelow(holder) -> {
                            footerLayoutParams.addRule(RelativeLayout.BELOW, R.id.viewStubContainer)
                            footerLayoutParams.addRule(alignEnd, R.id.viewStubContainer)
                            footerLayoutParams.removeRuleCompat(alignStart)
                            footerLayoutParams.removeRuleCompat(RelativeLayout.ALIGN_BOTTOM)
                            footerLayoutParams.removeRuleCompat(endOf)
                            footerLayoutParams.removeRuleCompat(startOf)
                            footerLayoutParams.removeRuleCompat(RelativeLayout.START_OF)
                        }
                        messageLayout.reverseBubble -> /* force footer on the left / at the start */ {
                            footerLayoutParams.addRule(RelativeLayout.START_OF, R.id.viewStubContainer)
                            footerLayoutParams.addRule(RelativeLayout.ALIGN_BOTTOM, R.id.viewStubContainer)
                            footerLayoutParams.removeRuleCompat(alignEnd)
                            footerLayoutParams.removeRuleCompat(alignStart)
                            footerLayoutParams.removeRuleCompat(endOf)
                            footerLayoutParams.removeRuleCompat(startOf)
                            footerLayoutParams.removeRuleCompat(RelativeLayout.BELOW)
                            // Reverse margins
                            footerMarginStartDp = views.bubbleFootView.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_footer_reverse_margin_start)
                            footerMarginEndDp = views.bubbleFootView.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_footer_reverse_margin_end)
                        }
                        else -> /* footer on the right / at the end */ {
                            footerLayoutParams.addRule(endOf, R.id.viewStubContainer)
                            footerLayoutParams.addRule(RelativeLayout.ALIGN_BOTTOM, R.id.viewStubContainer)
                            footerLayoutParams.removeRuleCompat(startOf)
                            footerLayoutParams.removeRuleCompat(alignEnd)
                            footerLayoutParams.removeRuleCompat(alignStart)
                            footerLayoutParams.removeRuleCompat(RelativeLayout.BELOW)
                            footerLayoutParams.removeRuleCompat(RelativeLayout.START_OF)
                        }
                    }
                    removeFooterOverlayStyle()
                }
                if (defaultRtl) {
                    footerLayoutParams.rightMargin = footerMarginStartDp
                    footerLayoutParams.leftMargin = footerMarginEndDp
                    views.bubbleMessageMemberNameView.gravity = Gravity.RIGHT
                } else {
                    footerLayoutParams.leftMargin = footerMarginStartDp
                    footerLayoutParams.rightMargin = footerMarginEndDp
                    views.bubbleMessageMemberNameView.gravity = Gravity.LEFT
                }
                // An overlaid footer (media timestamp) is aligned to the content container's edge; for a
                // media reply that container is as wide as the reply header, so shift the footer onto the
                // image's right edge instead of the empty gap beside a slim image.
                anchorOverlayFooterToMedia(bubbleDependentView, holder)
            }
            if (messageLayout.isPseudoBubble) {
                // We need to align the non-bubble member name view to pseudo bubbles
                if (messageLayout.reverseBubble) {
                    views.messageMemberNameView.setPaddingRelativeCompat(shortPadding, 0, longPadding, 0)
                } else {
                    views.messageMemberNameView.setPaddingRelativeCompat(longPadding, 0, shortPadding, 0)
                }
            }
        } else { // no bubbles
            bubbleView.backgroundCompat =null
            (bubbleView.layoutParams as ViewGroup.MarginLayoutParams).marginStartCompat =0
            (bubbleView.layoutParams as ViewGroup.MarginLayoutParams).marginEndCompat =0
            bubbleView.setPadding(0, 0, 0, 0)
            views.messageMemberNameView.setPadding(0, 0, 0, 0)
        }

        (views.bubbleView.layoutParams as FrameLayout.LayoutParams).gravity = if (messageLayout.reverseBubble) Gravity.END else Gravity.START
        setFlatRtl(views.reactionsContainer, if (messageLayout.reverseBubble) reverseDirection else defaultDirection, defaultDirection)
        views.messageThreadSummaryContainer.layoutDirectionCompat =if (messageLayout.reverseBubble) reverseDirection else defaultDirection
        // Layout is broken if bubbleView is RTL (for some reason, Android uses left/end padding for right/start as well...)
        setFlatRtl(views.bubbleView, View.LAYOUT_DIRECTION_LTR, defaultDirection)
    }

    private fun <H : BaseEventItem.BaseHolder> anchorOverlayFooterToMedia(bubbleDependentView: BubbleDependentView<H>, holder: H) {
        val footView = views.bubbleFootView
        footView.translationX = 0f
        footView.translationY = 0f
        if (!bubbleDependentView.allowFooterOverlay(holder, this) || bubbleDependentView.needsFooterReservation()) return
        // Sizes are only known after layout, so shift the (already-positioned) footer onto the media's
        // bottom-right corner then. translationX/Y are purely visual, so this can't feed a layout loop.
        footView.doOnLayout {
            val anchor = bubbleDependentView.footerOverlayAnchorView(holder)
            val parent = footView.parent as? View
            if (anchor != null && anchor.width > 0 && anchor.height > 0 && parent != null) {
                footView.translationX = (leftWithin(anchor, parent) + anchor.width - footView.right).toFloat()
                footView.translationY = (topWithin(anchor, parent) + anchor.height - footView.bottom).toFloat()
            } else {
                footView.translationX = 0f
                footView.translationY = 0f
            }
        }
    }

    // Left/top edge of [view] expressed in [ancestor]'s coordinate space (sum of offsets up the hierarchy).
    private fun leftWithin(view: View, ancestor: View): Int = offsetWithin(view, ancestor, horizontal = true)
    private fun topWithin(view: View, ancestor: View): Int = offsetWithin(view, ancestor, horizontal = false)

    private fun offsetWithin(view: View, ancestor: View, horizontal: Boolean): Int {
        var offset = 0
        var current: View = view
        while (current !== ancestor) {
            offset += if (horizontal) current.left else current.top
            current = current.parent as? View ?: break
        }
        return offset
    }

    private fun tintFooter(color: Int) {
        val tintList = ColorStateList(arrayOf(intArrayOf(0)), intArrayOf(color))
        ImageViewCompat.setImageTintList(views.bubbleFooterReadReceipt, tintList)
        views.bubbleFooterMessageTimeView.setTextColor(tintList)
    }

    private fun styleFooterOverlay(messageLayout: TimelineMessageLayout.ScBubble) {
        views.bubbleFootView.setBackgroundResource(messageLayout.bubbleAppearance.timestampOverlay)
        tintFooter(ThemeUtils.getColor(views.bubbleFootView.context, im.vector.lib.ui.styles.R.attr.timestamp_overlay_fg))
        val padding = views.bubbleFootView.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_footer_overlay_padding)
        views.bubbleFootView.setPaddingRelativeCompat(
                padding,
                padding,
                // compensate from inner view padding on the other side
                padding + views.bubbleFootView.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_footer_padding_compensation),
                padding
        )
    }

    private fun removeFooterOverlayStyle() {
        views.bubbleFootView.backgroundCompat =null
        tintFooter(ThemeUtils.getColor(views.bubbleFootView.context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary))
        views.bubbleFootView.setPaddingRelativeCompat(
                0,
                views.bubbleFootView.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_footer_noverlay_padding_top),
                0,
                views.bubbleFootView.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_footer_noverlay_padding_bottom)
        )
    }

    fun getFooterMeasures(informationData: MessageInformationData?): Array<Int> {
        val anonymousReadReceipt = BubbleThemeUtils.getVisibleAnonymousReadReceipts(informationData?.readReceiptAnonymous, informationData?.sentByMe ?: false)
        return getFooterMeasures(informationData, anonymousReadReceipt)
    }

    private fun getFooterMeasures(informationData: MessageInformationData?, anonymousReadReceipt: AnonymousReadReceipt): Array<Int> {
        if (informationData == null) {
            Timber.e("Calculating footer measures without information data")
        }
        val timeWidth: Int
        val timeHeight: Int
        if (informationData?.messageLayout is TimelineMessageLayout.ScBubble &&
                getBubbleTimeLocation(informationData.messageLayout as TimelineMessageLayout.ScBubble) == BubbleThemeUtils.BUBBLE_TIME_BOTTOM) {
            // Guess text width for name and time
            timeWidth = ceil(guessTextWidth(views.bubbleFooterMessageTimeView, informationData.time.toString())).toInt()
            timeHeight = ceil(views.bubbleFooterMessageTimeView.textSize).toInt() +
                    views.bubbleFooterMessageTimeView.paddingTop +
                    views.bubbleFooterMessageTimeView.paddingBottom
        } else {
            timeWidth = 0
            timeHeight = 0
        }
        val readReceiptWidth: Int
        val readReceiptHeight: Int
        if (anonymousReadReceipt == AnonymousReadReceipt.NONE) {
            readReceiptWidth = 0
            readReceiptHeight = 0
        } else {
            readReceiptWidth = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) views.bubbleFooterReadReceipt.maxWidth else 0) +
                    views.bubbleFooterReadReceipt.paddingLeft +
                    views.bubbleFooterReadReceipt.paddingRight
            readReceiptHeight = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) views.bubbleFooterReadReceipt.maxHeight else 0) +
                    views.bubbleFooterReadReceipt.paddingTop +
                    views.bubbleFooterReadReceipt.paddingBottom
        }

        val e2eWidth: Int
        val e2eHeight: Int
        if (informationData?.isPgp != true && informationData?.e2eDecoration in listOf(null, E2EDecoration.NONE)) {
            e2eWidth = 0
            e2eHeight = 0
        } else {
            e2eWidth = views.bubbleFooterMessageE2EDecoration.layoutParams.width + views.bubbleFooterMessageE2EDecoration.paddingLeft + views.bubbleFooterMessageE2EDecoration.paddingRight
            e2eHeight = views.bubbleFooterMessageE2EDecoration.layoutParams.height + views.bubbleFooterMessageE2EDecoration.paddingTop + views.bubbleFooterMessageE2EDecoration.paddingBottom
        }

        var footerWidth = timeWidth + readReceiptWidth + e2eWidth
        var footerHeight = max(max(timeHeight, readReceiptHeight), e2eHeight)
        // Reserve extra padding, if we do have actual content
        if (footerWidth > 0) {
            footerWidth += views.bubbleFootView.paddingLeft + views.bubbleFootView.paddingRight
        }
        if (footerHeight > 0) {
            footerHeight += views.bubbleFootView.paddingTop + views.bubbleFootView.paddingBottom
        }
        return arrayOf(footerWidth, footerHeight)
    }

    fun <H : VectorEpoxyHolder> getViewStubMinimumWidth(
            bubbleDependentView: BubbleDependentView<H>,
            holder: H,
            attributes: AbsMessageItem.Attributes,
            contentInBubble: Boolean,
            canHideSender: Boolean,
    ): Int {
        val messageLayout = attributes.informationData.messageLayout as? TimelineMessageLayout.ScBubble ?: return 0
        val memberName = attributes.informationData.memberName.toString()
        val time = attributes.informationData.time.toString()
        val result = if (contentInBubble) {
            if (getBubbleTimeLocation(messageLayout) == BUBBLE_TIME_BOTTOM) {
                if (attributes.informationData.messageLayout.showDisplayName && !canHideSender) {
                    ceil(guessTextWidth(views.bubbleMessageMemberNameView, memberName)).toInt()
                } else {
                    0
                }
            } else if (attributes.informationData.messageLayout.showTimestamp) {
                // Guess text width for name and time next to each other
                val text = if (attributes.informationData.messageLayout.showDisplayName) {
                    "$memberName $time"
                } else {
                    time
                }
                val textSize = if (attributes.informationData.messageLayout.showDisplayName) {
                    max(views.bubbleMessageMemberNameView.textSize, views.bubbleMessageTimeView.textSize)
                } else {
                    views.bubbleMessageTimeView.textSize
                }
                ceil(guessTextWidth(textSize, text)).toInt()
            } else {
                0
            }
        } else {
            0
        }
        // The guess above uses the *untruncated* name; cap it at the bubble's available width so an
        // excessively long display name ellipsizes instead of forcing the bubble (and so a narrow code
        // block / short message) out to the full name width.
        val res = views.bubbleMessageMemberNameView.resources
        val density = res.displayMetrics.density
        val reservedPx = ceil((44f + 8f) * density).toInt() + // avatar + its start margin
                res.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.item_event_message_state_size) +
                ceil((16f + 16f) * density).toInt() // send-state margins + bubble horizontal margin
        val maxNameWidth = (res.displayMetrics.widthPixels - reservedPx).coerceAtLeast(ceil(64f * density).toInt())
        return max(result.coerceAtMost(maxNameWidth), bubbleDependentView.getViewStubMinimumWidth(holder))
    }
}

fun canHideAvatars(attributes: AbsMessageItem.Attributes): Boolean {
    return attributes.informationData.sentByMe || attributes.informationData.isDirect
}

fun canHideSender(attributes: AbsMessageItem.Attributes): Boolean {
    return attributes.informationData.sentByMe ||
            (attributes.informationData.isDirect && attributes.informationData.senderId == attributes.informationData.dmChatPartnerId)
}

fun infoInBubbles(messageLayout: TimelineMessageLayout.ScBubble): Boolean {
    return (!messageLayout.singleSidedLayout) &&
            (messageLayout.isRealBubble || messageLayout.isPseudoBubble)
}

fun senderNameInBubble(messageLayout: TimelineMessageLayout.ScBubble): Boolean {
    return infoInBubbles(messageLayout) && !messageLayout.isPseudoBubble
}

fun getBubbleTimeLocation(messageLayout: TimelineMessageLayout.ScBubble): String {
    return if (messageLayout.singleSidedLayout) BUBBLE_TIME_TOP else BUBBLE_TIME_BOTTOM
}

fun setFlatRtl(layout: ViewGroup, direction: Int, childDirection: Int, depth: Int = 1) {
    layout.layoutDirectionCompat =direction
    for (child in layout.children) {
        if (depth > 1 && child is ViewGroup) {
            setFlatRtl(child, direction, childDirection, depth - 1)
        } else {
            child.layoutDirectionCompat =childDirection
        }
    }
}

// Static to use from classes that use simplified/non-sc layouts, e.g. item_timeline_event_base_noinfo
fun renderStubMessageLayout(messageLayout: TimelineMessageLayout, viewStubContainer: View) {
    if (messageLayout !is TimelineMessageLayout.ScBubble) {
        return
    }
    // Remove Element's TimelineContentStubContainerParams paddings, we don't want these
    viewStubContainer.setPadding(0, 0, 0, 0)
}
