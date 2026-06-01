/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.text.Spanned
import android.text.method.MovementMethod
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
import im.vector.app.core.epoxy.onLongClickIgnoringLinks
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.render.RichMessageBodyRenderer
import im.vector.app.features.home.room.detail.timeline.tools.findPillsAndProcess
import im.vector.app.features.html.BodySegment
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlRetriever
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlUiState
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlView
import im.vector.app.features.media.ImageContentRenderer
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import io.element.android.wysiwyg.EditorStyledTextView
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
    var movementMethod: MovementMethod? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var markwonPlugins: (List<MarkwonPlugin>)? = null

    @EpoxyAttribute
    var useRichTextEditorStyle: Boolean = false

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

    private val previewUrlViewUpdater = PreviewUrlViewUpdater()

    override fun bind(holder: Holder) {
        // Preview URL
        previewUrlViewUpdater.previewUrlView = holder.previewUrlView
        previewUrlViewUpdater.imageContentRenderer = imageContentRenderer
        val safePreviewUrlRetriever = previewUrlRetriever
        if (safePreviewUrlRetriever == null) {
            holder.previewUrlView.isVisible = false
        } else {
            safePreviewUrlRetriever.addListener(attributes.informationData.eventId, previewUrlViewUpdater)
        }
        holder.previewUrlView.delegate = previewUrlCallback
        holder.previewUrlView.renderMessageLayout(attributes.informationData.messageLayout)
        val segments = bodySegments
        val richBodyRendererLocal = richBodyRenderer
        if (segments != null && richBodyRendererLocal != null) {
            holder.plainMessageView?.isVisible = false
            holder.richMessageView?.isVisible = false
            val container = holder.requireRichBodyContainer()
            container.isVisible = true
            super.bind(holder)
            richBodyRendererLocal.render(
                    container = container,
                    segments = segments,
                    postProcessors = htmlPostProcessors ?: emptyArray(),
                    movementMethod = movementMethod,
                    onClick = { attributes.itemClickListener?.invoke(it) },
                    onLongClick = { attributes.itemLongClickListener?.onLongClick(it) ?: false },
                    noticeStyle = noticeStyle,
                    replyHeader = richReplyHeader,
            )
            renderSendState(container, null)
            return
        }
        if (useRichTextEditorStyle) {
            holder.plainMessageView?.isVisible = false
        } else {
            holder.richMessageView?.isVisible = false
        }
        holder.richBodyContainer?.isVisible = false
        val messageView: AppCompatTextView = if (useRichTextEditorStyle) holder.requireRichMessageView() else holder.requirePlainMessageView()
        messageView.isVisible = true
        if (useBigFont) {
            messageView.textSize = 44F
        } else {
            messageView.textSize = 15.5F
        }
        if (searchForPills) {
            message?.charSequence?.findPillsAndProcess(coroutineScope) {
                // mmm.. not sure this is so safe in regards to cell reuse
                it.bind(messageView)
            }
        }
        message?.charSequence.let { charSequence ->
            markwonPlugins?.forEach { plugin -> plugin.beforeSetText(messageView, charSequence as Spanned) }
        }
        super.bind(holder)
        messageView.movementMethod = movementMethod
        renderSendState(messageView, messageView)
        messageView.onClick(attributes.itemClickListener)
        messageView.onLongClickIgnoringLinks(attributes.itemLongClickListener)
        val defaultColorAttr = if (noticeStyle) {
            im.vector.lib.ui.styles.R.attr.vctr_content_secondary
        } else {
            im.vector.lib.ui.styles.R.attr.vctr_content_primary
        }
        messageView.setTextColor(resolveThemeColor(messageView, defaultColorAttr))
        messageView.setTextWithEmojiSupport(message?.charSequence, bindingOptions)
        markwonPlugins?.forEach { plugin -> plugin.afterSetText(messageView) }
    }

    private fun resolveThemeColor(view: View, attrRes: Int): Int {
        val tv = TypedValue()
        view.context.theme.resolveAttribute(attrRes, tv, true)
        return tv.data
    }

    private fun AppCompatTextView.setTextWithEmojiSupport(message: CharSequence?, bindingOptions: BindingOptions?) {
        if (bindingOptions?.canUseTextFuture.orFalse() && message != null) {
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
        previewUrlRetriever?.removeListener(attributes.informationData.eventId, previewUrlViewUpdater)
    }

    override fun getViewStubId() = STUB_ID

    class Holder : AbsMessageItem.Holder(STUB_ID) {
        val previewUrlView by bind<PreviewUrlView>(R.id.messageUrlPreview)
        private val richMessageStub by bind<ViewStub>(R.id.richMessageTextViewStub)
        private val plainMessageStub by bind<ViewStub>(R.id.plainMessageTextViewStub)
        private val richBodyContainerStub by bind<ViewStub>(R.id.richBodyContainerStub)
        var richMessageView: EditorStyledTextView? = null
            private set
        var plainMessageView: AppCompatTextView? = null
            private set
        var richBodyContainer: LinearLayout? = null
            private set

        fun requireRichBodyContainer(): LinearLayout {
            val view = richBodyContainer ?: richBodyContainerStub.inflate().findViewById<LinearLayout>(R.id.richBodyContainer)
            richBodyContainer = view
            return view
        }

        fun requireRichMessageView(): AppCompatTextView {
            val view = richMessageView ?: richMessageStub.inflate().findViewById<EditorStyledTextView>(R.id.messageTextView).also {
                // Required to ensure that `inlineCodeBgHelper` and `codeBlockBgHelper` are initialized
                it.updateStyle(
                        styleConfig = it.styleConfig,
                        mentionDisplayHandler = null,
                )
            }
            richMessageView = view
            return view
        }

        fun requirePlainMessageView(): AppCompatTextView {
            val view = plainMessageView ?: plainMessageStub.inflate().findViewById(R.id.messageTextView)
            plainMessageView = view
            return view
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
