/*
 * Copyright (c) 2020 New Vector Ltd
 * Copyright (c) 2022 SpiritCroc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.home.room.detail.timeline.reply

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateUtils
import android.text.method.MovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import im.vector.app.R
import im.vector.app.core.extensions.setTextOrHide
import im.vector.app.databinding.ViewInReplyToBinding
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayout
import im.vector.app.features.home.room.detail.timeline.tools.findPillsAndProcess
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CoroutineScope
import org.matrix.android.sdk.api.session.crypto.attachments.toElementToDecrypt
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContentWithFormattedBody
import org.matrix.android.sdk.api.session.room.model.message.MessageFileContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.model.message.getCaption
import org.matrix.android.sdk.api.session.room.model.message.getFileName
import org.matrix.android.sdk.api.session.room.model.message.getFileUrl
import org.matrix.android.sdk.api.session.room.model.message.getThumbnailUrl
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getLastMessageContent
import org.matrix.android.sdk.api.util.ContentUtils
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * A View to render a replied-to event.
 */
class InReplyToView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr), View.OnClickListener {

    private lateinit var views: ViewInReplyToBinding

    var delegate: TimelineEventController.InReplyToClickCallback? = null
    var sourceEventId: String? = null

    init {
        setupView()
    }

    private var state: PreviewReplyUiState = PreviewReplyUiState.NoReply

    private val maxThumbnailWidth = context.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.reply_thumbnail_max_width)
    private val maxThumbnailHeight = context.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.reply_thumbnail_height)

    fun render(
            newState: PreviewReplyUiState,
            retriever: ReplyPreviewRetriever,
            roomInformationData: MessageInformationData,
            movementMethod: MovementMethod?,
            itemLongClickListener: OnLongClickListener?,
            coroutineScope: CoroutineScope,
            force: Boolean = false
    ) {
        if (newState == state && !force) {
            return
        }

        state = newState

        when (newState) {
            PreviewReplyUiState.NoReply -> renderHidden()
            is PreviewReplyUiState.ReplyLoading -> renderLoading()
            is PreviewReplyUiState.Error -> renderError(newState)
            is PreviewReplyUiState.InReplyTo -> renderReplyTo(newState, retriever, roomInformationData, movementMethod, coroutineScope)
        }

        setOnLongClickListener(itemLongClickListener)
        // Somehow this one needs it additionally?
        views.replyTextView.setOnLongClickListener(itemLongClickListener)
    }

    override fun onClick(v: View?) {
        state.repliedToEventId?.let { delegate?.onRepliedToEventClicked(sourceEventId, it) }
    }

    // PRIVATE METHODS ****************************************************************************************************************************************

    private fun setupView() {
        inflate(context, R.layout.view_in_reply_to, this)
        views = ViewInReplyToBinding.bind(this)

        setOnClickListener(this)
        // Somehow this one needs it additionally?
        views.replyTextView.setOnClickListener(this)

        // Round the thumbnail corners (matches the timeline). renderHidden() draws the blurhash/solid
        // drawable directly without a corner transform, so clip the view itself to cover both cases.
        val radius = 8 * resources.displayMetrics.density
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            // clipToOutline / ViewOutlineProvider are API 21+ (anti-aliased).
            views.replyThumbnailView.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            views.replyThumbnailView.clipToOutline = true
        } else {
            // Pre-Lollipop: RoundedCornerImageView clips via canvas path instead.
            views.replyThumbnailView.setCornerRadii(radius, radius, radius, radius)
        }
    }

    private fun hideViews() {
        views.replyMemberNameView.isVisible = false
        views.replyTextView.isVisible = false
        // Clear stale text: the ExpandableViewLayout measures the text child even when hidden, so a
        // recycled view's old text would otherwise re-introduce the fade band over e.g. a thumbnail.
        views.replyTextView.text = null
        // Reset colour in case this recycled view previously rendered a (muted) notice.
        views.replyTextView.setTextColor(ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_primary))
        views.replyThumbnailView.isVisible = false
        views.replyAttachmentPill.isVisible = false
        renderFadeOut(null)
    }

    private fun renderHidden() {
        isVisible = false
    }

    private fun renderLoading() {
        hideViews()
        isVisible = true
        views.replyTextView.isVisible = true
        val color = ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
        views.replyTextView.text = SpannableString(context.getString(CommonStrings.in_reply_to_loading)).apply {
            setSpan(StyleSpan(Typeface.ITALIC), 0, length, 0)
            setSpan(ForegroundColorSpan(color), 0, length, 0)
        }
        views.inReplyToBar.setBackgroundColor(color)
    }

    private fun renderError(state: PreviewReplyUiState.Error) {
        hideViews()
        isVisible = true
        Timber.w(state.throwable, "Error rendering reply")
        views.replyTextView.isVisible = true
        val color = ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
        views.replyTextView.text = SpannableString(context.getString(CommonStrings.in_reply_to_error)).apply {
            setSpan(StyleSpan(Typeface.ITALIC), 0, length, 0)
            setSpan(ForegroundColorSpan(color), 0, length, 0)
        }
        views.inReplyToBar.setBackgroundColor(color)
    }

    private fun renderReplyTo(
            state: PreviewReplyUiState.InReplyTo,
            retriever: ReplyPreviewRetriever,
            roomInformationData: MessageInformationData,
            movementMethod: MovementMethod?,
            coroutineScope: CoroutineScope,
    ) {
        hideViews()
        isVisible = true
        views.replyMemberNameView.isVisible = true
        views.replyMemberNameView.text = state.senderName
        val senderColor = retriever.getMemberNameColor(state.event)
        views.replyMemberNameView.setTextColor(senderColor)
        views.inReplyToBar.setBackgroundColor(senderColor)
        if (state.event.root.isRedacted()) {
            renderRedacted()
        } else {
            renderFadeOut(roomInformationData)
            when (val content = state.event.getLastMessageContent()) {
                is MessageImageInfoContent -> renderImageThumbnailContent(content, state.event, retriever)
                is MessageVideoContent -> renderVideoThumbnailContent(content, state.event, retriever)
                // Files / voice / audio render as a non-interactive pill mirroring the timeline.
                is MessageFileContent -> renderAttachmentPill(R.drawable.ic_paperclip, content.getFileName())
                is MessageAudioContent -> renderAudioContent(content)
                is MessageContentWithFormattedBody -> renderTextContent(content, retriever, movementMethod, coroutineScope)
                else -> renderFallback(state.event, retriever)
            }
        }
    }

    private fun renderRedacted() {
        views.replyTextView.isVisible = true
        views.replyTextView.setText(CommonStrings.event_redacted)
    }

    private fun renderTextContent(
            content: MessageContentWithFormattedBody,
            retriever: ReplyPreviewRetriever,
            movementMethod: MovementMethod?,
            coroutineScope: CoroutineScope
    ) {
        views.replyTextView.isVisible = true

        // Quoted notices/system messages render muted (secondary), matching the timeline. Not italic
        // — this fork removed notice italics.
        val baseColorAttr = if (content.msgType == MessageType.MSGTYPE_NOTICE) {
            im.vector.lib.ui.styles.R.attr.vctr_content_secondary
        } else {
            im.vector.lib.ui.styles.R.attr.vctr_content_primary
        }
        views.replyTextView.setTextColor(ThemeUtils.getColor(context, baseColorAttr))

        // If the replied-to event is itself a reply, strip its quoted portion so we render only its
        // own message (a reply-chain shouldn't nest the grandparent's quote inside the preview).
        val formattedBody = content.formattedBody?.let { ContentUtils.extractUsefulTextFromHtmlReply(it) }

        val text = if (formattedBody != null) {
            val compressed = retriever.htmlCompressor.compress(formattedBody)
            val renderedFormattedBody = retriever.htmlRenderer.render(compressed, retriever.pillsPostProcessor)
            retriever.textRenderer.render(renderedFormattedBody)
        } else {
            ContentUtils.extractUsefulTextFromReply(content.body)
        }
        val markwonPlugins = retriever.htmlRenderer.plugins

        if (formattedBody != null) {
            text.findPillsAndProcess(coroutineScope) { pillImageSpan ->
                pillImageSpan.bind(views.replyTextView)
            }
        }
        text.let { charSequence ->
            if (charSequence is Spanned) {
                markwonPlugins.forEach { plugin -> plugin.beforeSetText(views.replyTextView, charSequence) }
            }
        }

        views.replyTextView.movementMethod = movementMethod
        // Set synchronously (not via PrecomputedTextCompat future): the async path measured the
        // ExpandableViewLayout before the text landed, leaving a recycled view stuck showing the
        // multi-line fade over a single-line reply.
        views.replyTextView.text = text
        markwonPlugins.forEach { plugin -> plugin.afterSetText(views.replyTextView) }
    }

    private fun renderImageThumbnailContent(
            content: MessageImageInfoContent,
            event: TimelineEvent,
            retriever: ReplyPreviewRetriever,
    ) {
        val data = ImageContentRenderer.Data(
                eventId = event.eventId,
                filename = content.getFileName(),
                mimeType = content.mimeType,
                url = content.getFileUrl(),
                elementToDecrypt = content.encryptedFileInfo?.toElementToDecrypt(),
                height = content.info?.height,
                maxHeight = maxThumbnailHeight,
                width = content.info?.width,
                maxWidth = maxThumbnailWidth,
                allowNonMxcUrls = false,
                blurHash = content.info?.blurHash,
        )
        renderThumbnailContent(data, content.getCaption(), event, retriever)
    }

    private fun renderVideoThumbnailContent(
            content: MessageVideoContent,
            event: TimelineEvent,
            retriever: ReplyPreviewRetriever,
    ) {
        val thumbnailData = ImageContentRenderer.Data(
                eventId = event.eventId,
                filename = content.getFileName(),
                mimeType = content.mimeType,
                url = content.videoInfo?.getThumbnailUrl(),
                elementToDecrypt = content.videoInfo?.thumbnailFile?.toElementToDecrypt(),
                height = content.videoInfo?.height,
                maxHeight = maxThumbnailHeight,
                width = content.videoInfo?.width,
                maxWidth = maxThumbnailWidth,
                allowNonMxcUrls = false,
                blurHash = content.videoInfo?.blurHash,
        )
        renderThumbnailContent(thumbnailData, content.getCaption(), event, retriever)
    }

    private fun renderThumbnailContent(
            mediaData: ImageContentRenderer.Data,
            caption: String?,
            event: TimelineEvent,
            retriever: ReplyPreviewRetriever,
    ) {
        views.replyThumbnailView.isVisible = true
        if (retriever.shouldHideMediaPreview(event)) {
            // Mirror the timeline's hidden-media state: blurhash or solid grey, and no caption text
            // (it would be illegible at this size). Tapping the preview jumps to the source.
            retriever.imageContentRenderer.renderHidden(
                    mediaData,
                    ImageContentRenderer.Mode.THUMBNAIL,
                    views.replyThumbnailView,
                    retriever.useSolidColorForHiddenMedia,
            )
        } else {
            retriever.imageContentRenderer.render(
                    mediaData,
                    ImageContentRenderer.Mode.THUMBNAIL,
                    views.replyThumbnailView
            )
            views.replyTextView.setTextOrHide(caption)
        }
    }

    private fun renderFallback(event: TimelineEvent, retriever: ReplyPreviewRetriever) {
        views.replyTextView.isVisible = true
        views.replyTextView.setTextColor(ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary))
        views.replyTextView.text = retriever.formatFallbackReply(event)
    }

    private fun renderAudioContent(content: MessageAudioContent) {
        val isVoice = content.voiceMessageIndicator != null
        val durationMs = content.audioInfo?.duration ?: 0
        if (isVoice) {
            renderAttachmentPill(R.drawable.ic_microphone, DateUtils.formatElapsedTime((durationMs / 1000).toLong()))
        } else {
            renderAttachmentPill(R.drawable.ic_attachment_voice_file, content.getFileName())
        }
    }

    private fun renderAttachmentPill(iconRes: Int, label: String?) {
        views.replyAttachmentPill.isVisible = true
        views.replyAttachmentIcon.setImageResource(iconRes)
        views.replyAttachmentLabel.text = label
    }

    /**
     * @param informationData The information data of the parent message, for background fade rendering info. Null to force expand to full height.
     */
    private fun renderFadeOut(informationData: MessageInformationData?) {
        if (informationData != null) {
            views.expandableReplyView.setExpanded(false)
            val chatBgColor = ThemeUtils.getColor(context, android.R.attr.colorBackground)
            val bgColor = when (val layout = informationData.messageLayout) {
                is TimelineMessageLayout.ScBubble -> {
                    if (informationData.sentByMe && !layout.singleSidedLayout) {
                        ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.sc_message_bg_outgoing)
                    } else {
                        ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.sc_message_bg_incoming)
                    }
                }
                is TimelineMessageLayout.Bubble -> {
                    if (layout.isPseudoBubble) {
                        0
                    } else {
                        val backgroundColorAttr = if (informationData.sentByMe) {
                            im.vector.lib.ui.styles.R.attr.vctr_message_bubble_outbound
                        } else {
                            im.vector.lib.ui.styles.R.attr.vctr_message_bubble_inbound
                        }
                        ThemeUtils.getColor(context, backgroundColorAttr)
                    }
                }
                is TimelineMessageLayout.Default -> {
                    // Non-bubble: the text sits directly on the chat background, so fade into it for
                    // a clean dissolve rather than a mismatched coloured glow.
                    chatBgColor
                }
            }
            val fadeView = views.expandableReplyView.getChildAt(1)
            // A real two-stop transparent->bg gradient (top transparent, bottom opaque) gives a
            // gradual fade; for transparent bubbles we resolve the effective colour over the chat bg.
            val effective = calculateEffectiveColor(bgColor, chatBgColor)
            fadeView.background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT, effective)
            )
        } else {
            views.expandableReplyView.setExpanded(true)
        }
    }

    /**
     * In case of transparent bubbles, we need to calculate the effective color before applying the fade effect.
     */
    private fun calculateEffectiveColor(fg: Int, bg: Int): Int {
        val fgAlpha = Color.alpha(fg)
        if (fgAlpha == 0xff) {
            return fg
        }
        val opacity = fgAlpha / (0xff).toFloat()
        val r = (Color.red(bg) * (1 - opacity) + Color.red(fg) * opacity).roundToInt()
        val g = (Color.green(bg) * (1 - opacity) + Color.green(fg) * opacity).roundToInt()
        val b = (Color.blue(bg) * (1 - opacity) + Color.blue(fg) * opacity).roundToInt()
        return Color.rgb(r, g, b)
    }
}
