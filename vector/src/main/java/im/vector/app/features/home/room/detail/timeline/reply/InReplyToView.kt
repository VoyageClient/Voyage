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
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import im.vector.app.R
import im.vector.app.databinding.ViewInReplyToBinding
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayout
import im.vector.app.features.home.room.detail.timeline.tools.attachmentPreviewText
import im.vector.app.features.home.room.detail.timeline.tools.findPillsAndProcess
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.html.BodySegment
import im.vector.app.features.html.HtmlBodySegmenter
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
import org.matrix.android.sdk.api.session.room.model.message.MessageStickerContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.model.message.getCaption
import org.matrix.android.sdk.api.session.room.model.message.getFileName
import org.matrix.android.sdk.api.session.room.model.message.getFileUrl
import org.matrix.android.sdk.api.session.room.model.message.getThumbnailUrl
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getLastMessageContent
import timber.log.Timber
import kotlin.math.roundToInt
import im.vector.app.core.extensions.backgroundCompat

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

    // Clicking the reply while its host message is still sending only has a local-echo id to jump
    // back to, which fails the later jump-to-bottom. Stay inert until the message is sent.
    var sourceIsSent: Boolean = true

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
            is PreviewReplyUiState.InReplyTo -> renderReplyTo(newState, retriever, roomInformationData, coroutineScope, itemLongClickListener)
        }

        setOnLongClickListener(itemLongClickListener)
        // Somehow this one needs it additionally?
        views.replyTextView.setOnLongClickListener(itemLongClickListener)
    }

    override fun onClick(v: View?) {
        if (!sourceIsSent) return
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
        views.replyTextView.movementMethod = null
        // A recycled reply must not keep a previous message's full-width-code stretch.
        views.replyTextView.fullWidthBlockCode = false
        views.replyThumbnailView.isVisible = false
        views.expandableReplyView.isVisible = true
        views.replyTextView.isVisible = true
        views.replyRichContainer.isVisible = false
        views.replyRichContainer.removeAllViews()
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
            coroutineScope: CoroutineScope,
            itemLongClickListener: OnLongClickListener?,
    ) {
        hideViews()
        isVisible = true
        views.replyMemberNameView.isVisible = true
        views.replyMemberNameView.text = state.senderName.prepareForDisplay()
        val senderColor = retriever.getMemberNameColor(state.event)
        views.replyMemberNameView.setTextColor(senderColor)
        views.inReplyToBar.setBackgroundColor(senderColor)
        if (state.event.root.isRedacted()) {
            renderRedacted()
        } else {
            renderFadeOut(roomInformationData)
            // PGP: show the decrypted plaintext for the quoted message, like the timeline.
            val pgpPlain = (state.event.getLastMessageContent() as? MessageContentWithFormattedBody)
                    ?.let { retriever.pgpDecryptor.peekDecryptedBody(it.body) }
            if (pgpPlain != null) {
                renderPgpReplyText(pgpPlain)
            } else when (val content = state.event.getLastMessageContent()) {
                is MessageImageInfoContent -> renderImageThumbnailContent(content, state.event, retriever, coroutineScope)
                is MessageVideoContent -> renderVideoThumbnailContent(content, state.event, retriever, coroutineScope)
                // Files / voice / audio render as a non-interactive pill mirroring the timeline.
                is MessageFileContent -> renderAttachmentPill(R.drawable.ic_paperclip, content.getFileName())
                is MessageAudioContent -> renderAudioContent(content)
                is MessageContentWithFormattedBody -> {
                    // Outside bubbles, stretch a block-code reply to the full timeline width like the
                    // timeline does; inside a bubble it should hug its content instead.
                    val fullWidthBlockCode = roomInformationData.messageLayout is TimelineMessageLayout.Default
                    renderTextContent(content, state.event, retriever, coroutineScope, itemLongClickListener, fullWidthBlockCode)
                }
                else -> renderFallback(state.event, retriever)
            }
        }
    }

    private fun renderPgpReplyText(text: String) {
        views.replyTextView.isVisible = true
        views.replyTextView.setTextColor(ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_primary))
        views.replyTextView.text = text.prepareForDisplay()
    }

    private fun renderRedacted() {
        views.replyTextView.isVisible = true
        views.replyTextView.setText(CommonStrings.event_redacted)
    }

    // No movement method anywhere in the preview: links/pills/spoilers stay inert so a tap anywhere
    // on the reply header jumps to the replied-to message instead.
    private fun renderTextContent(
            content: MessageContentWithFormattedBody,
            event: TimelineEvent,
            retriever: ReplyPreviewRetriever,
            coroutineScope: CoroutineScope,
            itemLongClickListener: OnLongClickListener?,
            fullWidthBlockCode: Boolean,
    ) {
        views.replyTextView.isVisible = true
        views.replyTextView.fullWidthBlockCode = fullWidthBlockCode

        // Quoted notices/system messages render muted (secondary), matching the timeline. Not italic
        // — this fork removed notice italics.
        val isNotice = content.msgType == MessageType.MSGTYPE_NOTICE
        val baseColorAttr = if (isNotice) {
            im.vector.lib.ui.styles.R.attr.vctr_content_secondary
        } else {
            im.vector.lib.ui.styles.R.attr.vctr_content_primary
        }
        views.replyTextView.setTextColor(ThemeUtils.getColor(context, baseColorAttr))

        // Reuse the reply body pre-rendered (off the main thread) by the retriever; a re-bind during a scroll
        // is a cache hit, so the HTML compress/render/linkify pipeline doesn't run on the UI thread.
        val rendered = retriever.renderedReplyBody(event)
        val compressed = rendered?.compressed
        if (compressed != null && (compressed.contains("<table", ignoreCase = true) || compressed.contains("<pre", ignoreCase = true))) {
            val segments = HtmlBodySegmenter.segment(compressed)
            // Only use the rich container when a real table/code block was extracted; otherwise fall
            // through so the text keeps its pill / linkify treatment.
            if (segments.any { it !is BodySegment.Html }) {
                renderRichContent(segments, retriever, isNotice, itemLongClickListener)
                return
            }
        }

        val text = rendered?.text ?: retriever.formatFallbackReply(event)
        val markwonPlugins = retriever.htmlRenderer.plugins

        text.findPillsAndProcess(coroutineScope) { pillImageSpan ->
            pillImageSpan.bind(views.replyTextView)
        }
        text.let { charSequence ->
            if (charSequence is Spanned) {
                markwonPlugins.forEach { plugin -> plugin.beforeSetText(views.replyTextView, charSequence) }
            }
        }

        // Set synchronously (not via PrecomputedTextCompat future): the async path measured the
        // ExpandableViewLayout before the text landed, leaving a recycled view stuck showing the
        // multi-line fade over a single-line reply.
        views.replyTextView.text = text.prepareForDisplay()
        markwonPlugins.forEach { plugin -> plugin.afterSetText(views.replyTextView) }
        // Markwon's CorePlugin.afterSetText installs a LinkMovementMethod when the view has none;
        // links here must stay inert so a tap snaps to the source message instead.
        views.replyTextView.movementMethod = null
    }

    // A reply to a message that contains a table or code block: render the full body into the rich
    // container via the same renderer the timeline uses, instead of the single TextView where table
    // cells collapse to plaintext and code wraps/loses its scroll + line numbers.
    private fun renderRichContent(
            segments: List<BodySegment>,
            retriever: ReplyPreviewRetriever,
            isNotice: Boolean,
            itemLongClickListener: OnLongClickListener?,
    ) {
        // Keep the expandable host (and its fade-out) visible; just swap the text view for the
        // rich container so long content fades out the same way long text does.
        views.replyTextView.isVisible = false
        views.replyRichContainer.isVisible = true
        retriever.richMessageBodyRenderer.render(
                container = views.replyRichContainer,
                segments = segments,
                postProcessors = arrayOf(retriever.pillsPostProcessor),
                movementMethod = null,
                onClick = { onClick(it) },
                onLongClick = { itemLongClickListener?.onLongClick(it) ?: false },
                noticeStyle = isNotice,
                interactive = false,
        )
    }

    private fun renderImageThumbnailContent(
            content: MessageImageInfoContent,
            event: TimelineEvent,
            retriever: ReplyPreviewRetriever,
            coroutineScope: CoroutineScope,
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
        val mode = ImageContentRenderer.previewMode(content is MessageStickerContent, content.mimeType)
        renderThumbnailContent(data, content.getCaption(), event, retriever, coroutineScope, mode)
    }

    private fun renderVideoThumbnailContent(
            content: MessageVideoContent,
            event: TimelineEvent,
            retriever: ReplyPreviewRetriever,
            coroutineScope: CoroutineScope,
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
        renderThumbnailContent(thumbnailData, content.getCaption(), event, retriever, coroutineScope)
    }

    private fun renderThumbnailContent(
            mediaData: ImageContentRenderer.Data,
            caption: String?,
            event: TimelineEvent,
            retriever: ReplyPreviewRetriever,
            coroutineScope: CoroutineScope,
            mode: ImageContentRenderer.Mode = ImageContentRenderer.Mode.THUMBNAIL,
    ) {
        views.replyThumbnailView.isVisible = true
        if (retriever.shouldHideMediaPreview(event)) {
            // Mirror the timeline's hidden-media state: blurhash or solid grey, and no caption text
            // (it would be illegible at this size). Tapping the preview jumps to the source.
            retriever.imageContentRenderer.renderHidden(
                    mediaData,
                    mode,
                    views.replyThumbnailView,
                    retriever.useSolidColorForHiddenMedia,
            )
        } else {
            retriever.imageContentRenderer.render(
                    mediaData,
                    mode,
                    views.replyThumbnailView
            )
            if (caption == null) {
                views.replyTextView.isVisible = false
            } else {
                renderCaptionText(caption, event, retriever, coroutineScope)
            }
        }
    }

    // Captions carry the same pills / custom emoticons as text bodies; render through the retriever's
    // cached pipeline and bind the spans — the raw string would show emotes as literal :shortcode:.
    private fun renderCaptionText(caption: String, event: TimelineEvent, retriever: ReplyPreviewRetriever, coroutineScope: CoroutineScope) {
        val text = retriever.renderedReplyBody(event)?.text ?: caption
        views.replyTextView.isVisible = true
        views.replyTextView.setTextColor(ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_primary))
        val markwonPlugins = retriever.htmlRenderer.plugins
        text.findPillsAndProcess(coroutineScope) { it.bind(views.replyTextView) }
        if (text is Spanned) {
            markwonPlugins.forEach { plugin -> plugin.beforeSetText(views.replyTextView, text) }
        }
        views.replyTextView.text = text.prepareForDisplay()
        markwonPlugins.forEach { plugin -> plugin.afterSetText(views.replyTextView) }
        views.replyTextView.movementMethod = null
    }

    private fun renderFallback(event: TimelineEvent, retriever: ReplyPreviewRetriever) {
        views.replyTextView.isVisible = true
        views.replyTextView.setTextColor(ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary))
        views.replyTextView.text = retriever.formatFallbackReply(event)
    }

    private fun renderAudioContent(content: MessageAudioContent) {
        val formattedDuration = DateUtils.formatElapsedTime(((content.audioInfo?.duration ?: 0) / 1000).toLong())
        if (content.voiceMessageIndicator != null) {
            renderAttachmentPill(R.drawable.ic_microphone, context.getString(CommonStrings.voice_message_reply_content, formattedDuration))
        } else {
            renderAttachmentPill(R.drawable.ic_attachment_voice_file, context.getString(CommonStrings.audio_message_reply_content, content.body, formattedDuration))
        }
    }

    private fun renderAttachmentPill(iconRes: Int, label: String?) {
        // Use the same inline pill as the composer / long-press preview so all three match.
        views.replyTextView.isVisible = true
        views.replyTextView.setTextColor(ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_primary))
        views.replyTextView.text = attachmentPreviewText(context, iconRes, label.orEmpty())
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
            fadeView.backgroundCompat = GradientDrawable(
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
