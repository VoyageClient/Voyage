/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.text.Spanned
import android.util.TypedValue
import android.view.View
import android.view.ViewStub
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.text.PrecomputedTextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.epoxy.onLongClickIgnoringLinksSelectingCode
import im.vector.app.core.ui.views.AbstractFooteredTextView
import im.vector.app.core.utils.setReadOnlySelectable
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.render.RichMessageBodyRenderer
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayout
import im.vector.app.features.home.room.detail.timeline.tools.findPillsAndProcess
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlRetriever
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlUiState
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlView
import im.vector.app.features.home.room.detail.timeline.view.ScMessageBubbleWrapView
import im.vector.app.features.html.BodySegment
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.MediaContentRevealManager
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import io.noties.markwon.MarkwonPlugin
import org.matrix.android.sdk.api.extensions.orFalse

@EpoxyModelClass
abstract class MessageTextItem : AbsMessageItem<MessageTextItem.Holder>() {

    @EpoxyAttribute
    var searchForPills: Boolean = false

    @EpoxyAttribute
    var message: EpoxyCharSequence? = null

    @EpoxyAttribute
    var bindingOptions: BindingOptions? = null

    @EpoxyAttribute
    var useBigFont: Boolean = false

    @EpoxyAttribute
    var previewUrlRetriever: PreviewUrlRetriever? = null

    @EpoxyAttribute
    var previewUrlCallback: TimelineEventController.PreviewUrlCallback? = null

    @EpoxyAttribute
    var imageContentRenderer: ImageContentRenderer? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var markwonPlugins: (List<MarkwonPlugin>)? = null

    @EpoxyAttribute
    var noticeStyle: Boolean = false

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var bodySegments: List<BodySegment>? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var richReplyHeader: CharSequence? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var richBodyRenderer: RichMessageBodyRenderer? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var htmlPostProcessors: Array<EventHtmlRenderer.PostProcessor>? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var urlClickCallback: TimelineEventController.UrlClickCallback? = null

    // Body variant with inline images replaced by grey placeholders, shown while the room hides media
    // and the message hasn't been revealed. Tapping the message reveals all its inline images.
    @EpoxyAttribute
    var blockedMessage: EpoxyCharSequence? = null

    @EpoxyAttribute
    var blockedBindingOptions: BindingOptions? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var mediaRevealManager: MediaContentRevealManager? = null

    private val previewUrlViewUpdater = PreviewUrlViewUpdater()

    override fun bind(holder: Holder) = im.vector.app.core.utils.PerfTrace.time("timeline.bind.text") {
        bindInternal(holder)
    }

    private fun bindInternal(holder: Holder) {
        // Preview URL
        previewUrlViewUpdater.previewUrlView = holder.previewUrlView
        previewUrlViewUpdater.imageContentRenderer = imageContentRenderer
        val safePreviewUrlRetriever = previewUrlRetriever
        if (safePreviewUrlRetriever == null) {
            holder.previewUrlView.isVisible = false
        } else {
            safePreviewUrlRetriever.addListener(attributes.informationData.stableId, previewUrlViewUpdater)
        }
        holder.previewUrlView.delegate = previewUrlCallback
        holder.previewUrlView.renderMessageLayout(attributes.informationData.messageLayout)
        val segments = bodySegments
        val richBodyRendererLocal = richBodyRenderer
        if (segments != null && richBodyRendererLocal != null) {
            holder.plainMessageView?.isVisible = false
            val container = holder.requireRichBodyContainer()
            container.isVisible = true
            im.vector.app.core.utils.PerfTrace.time("bind.text.super") { super.bind(holder) }
            im.vector.app.core.utils.PerfTrace.time("bind.text.richRender") {
                richBodyRendererLocal.render(
                        container = container,
                        segments = segments,
                        postProcessors = htmlPostProcessors ?: emptyArray(),
                        movementMethod = movementMethod,
                        onClick = { attributes.itemClickListener?.invoke(it) },
                        onLongClick = { attributes.itemLongClickListener?.onLongClick(it) ?: false },
                        noticeStyle = noticeStyle,
                        replyHeader = richReplyHeader,
                        urlClickCallback = urlClickCallback,
                        fullBleed = attributes.informationData.messageLayout.let { l ->
                            // No visible bubble (modern layout, or SC with bubbles turned off): stretch
                            // code to the row edge. A real/pseudo bubble hugs its content instead.
                            l is TimelineMessageLayout.Default ||
                                    (l is TimelineMessageLayout.ScBubble && !l.isRealBubble && !l.isPseudoBubble)
                        },
                )
            }
            renderSendState(container, null)
            return
        }
        holder.richBodyContainer?.isVisible = false
        val messageView: AppCompatTextView = holder.requirePlainMessageView()
        messageView.isVisible = true
        (messageView as? AbstractFooteredTextView)?.fullWidthBlockCode =
                attributes.informationData.messageLayout is TimelineMessageLayout.Default
        if (useBigFont) {
            messageView.textSize = 44F
        } else {
            messageView.textSize = 15.5F
        }
        val showBlocked = blockedMessage != null &&
                mediaRevealManager?.isRevealed(attributes.informationData.stableId) != true
        val activeMessage = (if (showBlocked) blockedMessage else message)?.charSequence
        val activeOptions = if (showBlocked) blockedBindingOptions else bindingOptions
        if (searchForPills) {
            activeMessage?.findPillsAndProcess(coroutineScope) {
                // mmm.. not sure this is so safe in regards to cell reuse
                it.bind(messageView)
            }
        }
        im.vector.app.core.utils.PerfTrace.time("bind.text.beforeText") {
            activeMessage.let { charSequence ->
                markwonPlugins?.forEach { plugin -> plugin.beforeSetText(messageView, charSequence as Spanned) }
            }
        }
        im.vector.app.core.utils.PerfTrace.time("bind.text.super") { super.bind(holder) }
        messageView.setReadOnlySelectable(true)
        messageView.movementMethod = movementMethod
        renderSendState(messageView, messageView)
        if (showBlocked) {
            // Tap any blocked inline image to reveal all of this message's images, then re-bind.
            messageView.onClick {
                mediaRevealManager?.reveal(attributes.informationData.stableId)
                bind(holder)
            }
        } else {
            messageView.onClick(attributes.itemClickListener)
        }
        messageView.onLongClickIgnoringLinksSelectingCode(attributes.itemLongClickListener)
        val defaultColorAttr = if (noticeStyle) {
            im.vector.lib.ui.styles.R.attr.vctr_content_secondary
        } else {
            im.vector.lib.ui.styles.R.attr.vctr_content_primary
        }
        messageView.setTextColor(resolveThemeColor(messageView, defaultColorAttr))
        im.vector.app.core.utils.PerfTrace.time("bind.text.setText") {
            messageView.setTextWithEmojiSupport(activeMessage, activeOptions)
        }
        im.vector.app.core.utils.PerfTrace.time("bind.text.afterText") {
            markwonPlugins?.forEach { plugin -> plugin.afterSetText(messageView) }
        }
    }

    private fun resolveThemeColor(view: View, attrRes: Int): Int {
        val tv = TypedValue()
        view.context.theme.resolveAttribute(attrRes, tv, true)
        return tv.data
    }

    private fun AppCompatTextView.setTextWithEmojiSupport(message: CharSequence?, bindingOptions: BindingOptions?) {
        // Selectable views need a spannable buffer; don't hand them precomputed text.
        if (bindingOptions?.canUseTextFuture.orFalse() && message != null && !isTextSelectable) {
            val textFuture = PrecomputedTextCompat.getTextFuture(message, TextViewCompat.getTextMetricsParams(this), null)
            setTextFuture(textFuture)
        } else {
            setTextFuture(null)
            text = message
        }
    }

    override fun unbind(holder: Holder) {
        super.unbind(holder)
        previewUrlViewUpdater.previewUrlView = null
        previewUrlViewUpdater.imageContentRenderer = null
        previewUrlRetriever?.removeListener(attributes.informationData.stableId, previewUrlViewUpdater)
    }

    // Overlay the inline timestamp on a single text view; the rich-body (reply) path keeps the footer below.
    override fun allowFooterOverlay(holder: Holder, bubbleWrapView: ScMessageBubbleWrapView): Boolean = bodySegments == null

    override fun needsFooterReservation(): Boolean = bodySegments == null

    override fun reserveFooterSpace(holder: Holder, width: Int, height: Int) {
        val footeredView = holder.footeredMessageView() ?: return
        footeredView.footerWidth = width
        footeredView.footerHeight = height
        footeredView.getAppCompatTextView().requestLayout()
    }

    override fun getViewStubId() = STUB_ID

    class Holder : AbsMessageItem.Holder(STUB_ID) {
        val previewUrlView by bind<PreviewUrlView>(R.id.messageUrlPreview)
        private val plainMessageStub by bind<ViewStub>(R.id.plainMessageTextViewStub)
        private val richBodyContainerStub by bind<ViewStub>(R.id.richBodyContainerStub)
        var plainMessageView: AppCompatTextView? = null
            private set
        var richBodyContainer: LinearLayout? = null
            private set

        fun requireRichBodyContainer(): LinearLayout {
            val view = richBodyContainer ?: richBodyContainerStub.inflate().findViewById<LinearLayout>(R.id.richBodyContainer)
            richBodyContainer = view
            return view
        }

        fun requirePlainMessageView(): AppCompatTextView {
            val view = plainMessageView ?: plainMessageStub.inflate().findViewById(R.id.messageTextView)
            plainMessageView = view
            return view
        }

        fun footeredMessageView(): AbstractFooteredTextView? {
            return plainMessageView as? AbstractFooteredTextView
        }
    }

    inner class PreviewUrlViewUpdater : PreviewUrlRetriever.PreviewUrlRetrieverListener {
        var previewUrlView: PreviewUrlView? = null
        var imageContentRenderer: ImageContentRenderer? = null

        override fun onStateUpdated(state: PreviewUrlUiState) {
            val safeImageContentRenderer = imageContentRenderer
            if (safeImageContentRenderer == null) {
                previewUrlView?.isVisible = false
                return
            }
            previewUrlView?.render(state, safeImageContentRenderer)
        }
    }

    companion object {
        private val STUB_ID = R.id.messageContentTextStub
    }
}
