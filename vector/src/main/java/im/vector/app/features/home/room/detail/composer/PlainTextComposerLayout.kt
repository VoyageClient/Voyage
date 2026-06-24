/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.text.toSpannable
import androidx.core.view.isVisible
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.extensions.getVectorLastMessageContent
import im.vector.app.core.extensions.setTextIfDifferent
import im.vector.app.core.extensions.showKeyboard
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.databinding.ComposerLayoutBinding
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.format.NoticeEventFormatter
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.app.features.home.room.detail.timeline.image.buildImageContentRendererData
import im.vector.app.features.home.room.detail.timeline.render.RichMessageBodyRenderer
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.html.HtmlBodySegmenter
import im.vector.app.features.html.PillImageSpan
import im.vector.app.features.html.PillsPostProcessor
import im.vector.app.features.html.expandPillSpans
import im.vector.app.features.html.setPillSpan
import im.vector.app.features.html.VectorHtmlCompressor
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.MediaContentRevealManager
import im.vector.app.features.media.shouldHideMediaPreview
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import org.commonmark.parser.Parser
import org.matrix.android.sdk.api.session.getUserOrDefault
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.permalinks.PermalinkData
import org.matrix.android.sdk.api.session.permalinks.PermalinkParser
import org.matrix.android.sdk.api.session.room.send.MatrixItemSpan
import org.matrix.android.sdk.api.util.toMatrixItem
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageBeaconInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContentWithFormattedBody
import org.matrix.android.sdk.api.session.room.model.message.MessageEndPollContent
import org.matrix.android.sdk.api.session.room.model.message.MessageFormat
import org.matrix.android.sdk.api.session.room.model.message.MessagePollContent
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.util.ContentUtils
import org.matrix.android.sdk.api.util.MatrixItem
import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject

/**
 * Encapsulate the timeline composer UX.
 */
@AndroidEntryPoint
class PlainTextComposerLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), MessageComposerView {

    @Inject lateinit var avatarRenderer: AvatarRenderer
    @Inject lateinit var matrixItemColorProvider: MatrixItemColorProvider
    @Inject lateinit var noticeEventFormatter: NoticeEventFormatter
    @Inject lateinit var eventHtmlRenderer: EventHtmlRenderer
    @Inject lateinit var htmlCompressor: VectorHtmlCompressor
    @Inject lateinit var richMessageBodyRenderer: RichMessageBodyRenderer
    @Inject lateinit var dimensionConverter: DimensionConverter
    @Inject lateinit var imageContentRenderer: ImageContentRenderer
    @Inject lateinit var pillsPostProcessorFactory: PillsPostProcessor.Factory
    @Inject lateinit var activeSessionHolder: ActiveSessionHolder
    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var mediaContentRevealManager: MediaContentRevealManager

    private val views: ComposerLayoutBinding

    // The replied-to/related event currently shown in the preview, so its media can be re-rendered
    // in place when revealed elsewhere.
    private var relatedMessageEvent: TimelineEvent? = null

    override var callback: Callback? = null

    override val text: Editable?
        get() = views.composerEditText.text

    override val formattedText: String? = null

    override val editText: EditText
        get() = views.composerEditText

    @Suppress("RedundantNullableReturnType")
    override val emojiButton: ImageButton?
        get() = views.composerEmojiButton

    override val sendButton: ImageButton
        get() = views.sendButton

    override val attachmentButton: ImageButton
        get() = views.attachmentButton

    init {
        inflate(context, R.layout.composer_layout, this)
        views = ComposerLayoutBinding.bind(this)

        views.composerEditText.maxLines = MessageComposerView.MAX_LINES_WHEN_COLLAPSED

        // Round the replied-to image corners. Glide's RoundedCorners only transforms the loaded
        // bitmap, so a still-loading blurhash placeholder (Drawable) would otherwise show square.
        val imageCornerRadius = 8 * resources.displayMetrics.density
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            // clipToOutline / ViewOutlineProvider are API 21+ (anti-aliased).
            views.composerRelatedMessageImage.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, imageCornerRadius)
                }
            }
            views.composerRelatedMessageImage.clipToOutline = true
        } else {
            // Pre-Lollipop: RoundedCornerImageView clips via canvas path instead.
            views.composerRelatedMessageImage.setCornerRadii(imageCornerRadius, imageCornerRadius, imageCornerRadius, imageCornerRadius)
        }

        // Fade the capped preview into the composer's surface background (matches the reply header's
        // fade-out) rather than clipping content with a trailing ellipsis.
        val surfaceColor = ThemeUtils.getColor(context, com.google.android.material.R.attr.colorSurface)
        views.composerRelatedMessageFade.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, surfaceColor)
        )

        collapse()

        views.composerEditText.callback = object : ComposerEditText.Callback {
            override fun onRichContentSelected(contentUri: Uri): Boolean {
                return callback?.onRichContentSelected(contentUri) ?: false
            }

            override fun onTextChanged(text: CharSequence) {
                callback?.onTextChanged(text)
            }
        }
        views.composerRelatedMessageCloseButton.setOnClickListener {
            collapse()
            callback?.onCloseRelatedMessage()
        }

        views.sendButton.setOnClickListener {
            val textMessage = text?.toSpannable()?.expandPillSpans() ?: ""
            callback?.onSendMessage(textMessage)
        }

        views.attachmentButton.setOnClickListener {
            callback?.onAddAttachment()
        }
    }

    private fun collapse(transitionComplete: (() -> Unit)? = null) {
        views.relatedMessageGroup.isVisible = false
        transitionComplete?.invoke()
        callback?.onExpandOrCompactChange()
    }

    private fun expand(transitionComplete: (() -> Unit)? = null) {
        views.relatedMessageGroup.isVisible = true
        transitionComplete?.invoke()
        callback?.onExpandOrCompactChange()
    }

    override fun setTextIfDifferent(text: CharSequence?): Boolean {
        return views.composerEditText.setTextIfDifferent(text)
    }

    private fun renderRelatedMessageImage(event: TimelineEvent, crossFade: Boolean = false): Boolean {
        val data = event.buildImageContentRendererData(dimensionConverter.dpToPx(66))
        return if (data != null) {
            val session = activeSessionHolder.getSafeActiveSession()
            val hidden = session != null && shouldHideMediaPreview(event, session, vectorPreferences, mediaContentRevealManager)
            if (hidden) {
                imageContentRenderer.renderHidden(data, ImageContentRenderer.Mode.THUMBNAIL, views.composerRelatedMessageImage, vectorPreferences.useSolidColorForHiddenMedia())
            } else {
                imageContentRenderer.render(data, ImageContentRenderer.Mode.THUMBNAIL, views.composerRelatedMessageImage, crossFade = crossFade)
            }
            true
        } else {
            imageContentRenderer.clear(views.composerRelatedMessageImage)
            false
        }
    }

    override fun refreshRelatedMessageMedia() {
        val event = relatedMessageEvent ?: return
        if (!views.relatedMessageGroup.isVisible) return
        // Cross-fade from the blurhash/solid placeholder to the revealed image.
        views.composerRelatedMessageImage.isVisible = renderRelatedMessageImage(event, crossFade = true)
    }

    override fun getDraftContent(): CharSequence = serializeMentionPills(text ?: "")

    // Serialise mention pills (MatrixItemSpan) to matrix.to markdown links so they survive the draft's
    // String round-trip; non-mention text is left verbatim. Returns the plain text when there are none.
    private fun serializeMentionPills(source: CharSequence): String {
        val spannable = source.toSpannable()
        val spans = spannable.getSpans(0, spannable.length, MatrixItemSpan::class.java)
                .sortedBy { spannable.getSpanStart(it) }
        if (spans.isEmpty()) return source.toString()
        return buildString {
            var index = 0
            spans.forEach { span ->
                val start = spannable.getSpanStart(span)
                val end = spannable.getSpanEnd(span)
                if (start < index) return@forEach
                append(spannable, index, start)
                // The backing text is a placeholder char, so take the label from the matrix item.
                append("[").append(span.matrixItem.getBestName()).append("]")
                append("(https://matrix.to/#/").append(span.matrixItem.id).append(")")
                index = end
            }
            append(spannable, index, spannable.length)
        }
    }

    private val mentionLinkRegex = Regex("""\[([^]]+)]\((https://matrix\.to/#/[^)]+)\)""")

    // Inverse of serializeMentionPills: turn matrix.to markdown links back into PillImageSpans.
    private fun reconstructMentionPills(source: CharSequence): CharSequence {
        if (!source.contains("https://matrix.to/#/")) return source
        val session = activeSessionHolder.getSafeActiveSession() ?: return source
        val out = SpannableStringBuilder()
        var index = 0
        mentionLinkRegex.findAll(source).forEach { match ->
            out.append(source, index, match.range.first)
            val label = match.groupValues[1]
            val matrixItem = when (val data = PermalinkParser.parse(match.groupValues[2])) {
                is PermalinkData.UserLink -> session.getUserOrDefault(data.userId).toMatrixItem()
                is PermalinkData.RoomLink -> session.getRoomSummary(data.roomIdOrAlias)?.toMatrixItem()
                else -> null
            }
            if (matrixItem != null) {
                val start = out.length
                out.append(label)
                val span = PillImageSpan(GlideApp.with(this), avatarRenderer, context, matrixItem).also { it.bind(editText) }
                out.setPillSpan(span, start, start + label.length)
            } else {
                out.append(source, match.range.first, match.range.last + 1)
            }
            index = match.range.last + 1
        }
        out.append(source, index, source.length)
        return out
    }

    override fun renderComposerMode(mode: MessageComposerMode) {
        val specialMode = mode as? MessageComposerMode.Special
        if (specialMode != null) {
            renderSpecialMode(specialMode)
        } else if (mode is MessageComposerMode.Normal) {
            collapse()
            // Reconstruct mention pills from a restored draft's matrix.to markdown links. For live
            // content (already-spanned, no markdown), this is a no-op and the existing pills are kept.
            val content = reconstructMentionPills(mode.content ?: "")
            if (editText.text?.toString() != content.toString()) {
                editText.setTextIfDifferent(content)
            }
        }

        views.sendButton.apply {
            if (mode is MessageComposerMode.Edit) {
                contentDescription = resources.getString(CommonStrings.action_save)
                setImageResource(R.drawable.ic_composer_rich_text_save)
            } else {
                contentDescription = resources.getString(CommonStrings.action_send)
                setImageResource(R.drawable.ic_rich_composer_send)
            }
        }
    }

    private fun renderSpecialMode(specialMode: MessageComposerMode.Special) {
        val event = specialMode.event
        val defaultContent = specialMode.defaultContent

        val iconRes: Int = when (specialMode) {
            is MessageComposerMode.Reply -> R.drawable.ic_reply
            is MessageComposerMode.Edit -> R.drawable.ic_edit
            is MessageComposerMode.Quote -> R.drawable.ic_quote
        }

        val pillsPostProcessor = pillsPostProcessorFactory.create(event.roomId)

        // switch to expanded bar
        views.composerRelatedMessageTitle.apply {
            text = event.senderInfo.disambiguatedDisplayName
            setTextColor(matrixItemColorProvider.getColor(MatrixItem.UserItem(event.root.senderId ?: "@")))
        }

        val messageContent: MessageContent? = event.getVectorLastMessageContent()
        val nonFormattedBody = when {
            event.root.isRedacted() -> noticeEventFormatter.formatRedactedEvent(event.root)
            messageContent is MessageAudioContent -> getAudioContentBodyText(messageContent)
            messageContent is MessagePollContent -> messageContent.getBestPollCreationInfo()?.question?.getBestQuestion()
            messageContent is MessageBeaconInfoContent -> resources.getString(CommonStrings.live_location_description)
            messageContent is MessageEndPollContent -> resources.getString(CommonStrings.message_reply_to_ended_poll_preview)
            // The composer preview never shows a map, so location is always the notice text.
            messageContent?.msgType == MessageType.MSGTYPE_LOCATION ->
                noticeEventFormatter.formatLocationNotice(event.root, event.senderInfo.disambiguatedDisplayName)
            // Non-message event (membership change, reaction, …): show the notice text, falling back
            // to a debug line (known type) or the accent "not handled" notice (unknown type).
            messageContent == null -> noticeEventFormatter.format(event, isDm = false)
                    ?: noticeEventFormatter.formatDebugOrUnhandled(event.root)
            else -> messageContent.body
        }
        var formattedBody: CharSequence? = null
        var renderedTable = false
        // m.text, m.notice and m.emote all carry a formatted_body; render it so HTML-only bodies
        // (common for bot m.notice messages) aren't shown blank or as raw markup.
        val isFormattableText = messageContent?.msgType == MessageType.MSGTYPE_TEXT ||
                messageContent?.msgType == MessageType.MSGTYPE_NOTICE ||
                messageContent?.msgType == MessageType.MSGTYPE_EMOTE
        if (isFormattableText && messageContent is MessageContentWithFormattedBody &&
                messageContent.format == MessageFormat.FORMAT_MATRIX_HTML) {
            val htmlToRender = messageContent.formattedBody?.let { ContentUtils.extractUsefulTextFromHtmlReply(it) }
            val compressed = htmlToRender?.let { htmlCompressor.compress(it) }
            if (compressed != null && compressed.contains("<table", ignoreCase = true)) {
                renderRichTablePreview(compressed, pillsPostProcessor)
                renderedTable = true
            } else if (compressed != null) {
                // Render the HTML string (not a pre-parsed commonmark Node) so the root-tag
                // post-processor runs and code blocks / nested tags render, as in the timeline.
                formattedBody = eventHtmlRenderer.render(compressed, pillsPostProcessor)
            } else {
                val parser = Parser.builder().build()
                val document = parser.parse(ContentUtils.extractUsefulTextFromReply(messageContent.body))
                formattedBody = eventHtmlRenderer.render(document, pillsPostProcessor)
            }
        }
        if (!renderedTable) {
            views.composerRelatedMessageRichContainer.isVisible = false
            views.composerRelatedMessageRichContainer.removeAllViews()
        }
        views.composerRelatedMessageContent.isVisible = !renderedTable
        eventHtmlRenderer.setTextWithPlugins(views.composerRelatedMessageContent, formattedBody ?: nonFormattedBody)
        // Muted grey for non-message notices and m.notice messages (which render grey in the
        // timeline), normal text colour for everything else.
        val contentColorAttr = if (messageContent == null || messageContent.msgType == MessageType.MSGTYPE_NOTICE) {
            im.vector.lib.ui.styles.R.attr.vctr_content_secondary
        } else {
            im.vector.lib.ui.styles.R.attr.vctr_message_text_color
        }
        views.composerRelatedMessageContent.setTextColor(ThemeUtils.getColor(context, contentColorAttr))

        // Image Event
        relatedMessageEvent = event
        val isImageVisible = renderRelatedMessageImage(event)
        views.composerRelatedMessageImage.isVisible = isImageVisible

        views.composerRelatedMessageActionIcon.setImageDrawable(ContextCompat.getDrawable(context, iconRes))

        avatarRenderer.render(event.senderInfo.toMatrixItem(), views.composerRelatedMessageAvatar)

        val content = if (specialMode is MessageComposerMode.Edit) {
            // Drop block leading-margin spans (blockquote, code, list indents): in an EditText they
            // trigger the Android cursor-vs-text offset bug while editing.
            (formattedBody ?: defaultContent).withoutLeadingMargins()
        } else {
            defaultContent
        }

        if (views.composerEditText.text?.toString() != content.toString()) {
            views.composerEditText.setText(content)
        }

        expand {
            // need to do it here also when not using quick reply
            if (isVisible) {
                showKeyboard(andRequestFocus = true)
            }
            views.composerRelatedMessageImage.isVisible = isImageVisible
        }
    }

    // Render a table-containing reply/edit preview as real tables via the timeline's renderer,
    // instead of the single-line TextView that would collapse the cells into unreadable plaintext.
    private fun renderRichTablePreview(compressed: String, pillsPostProcessor: PillsPostProcessor) {
        views.composerRelatedMessageRichContainer.isVisible = true
        richMessageBodyRenderer.render(
                container = views.composerRelatedMessageRichContainer,
                segments = HtmlBodySegmenter.segment(compressed),
                postProcessors = arrayOf(pillsPostProcessor),
                movementMethod = null,
                onClick = {},
                onLongClick = { false },
        )
    }

    private fun CharSequence.withoutLeadingMargins(): CharSequence {
        if (this !is Spanned) return this
        val spans = getSpans(0, length, LeadingMarginSpan::class.java)
        if (spans.isEmpty()) return this
        return SpannableStringBuilder(this).also { ssb -> spans.forEach { ssb.removeSpan(it) } }
    }

    private fun getAudioContentBodyText(messageContent: MessageAudioContent): String {
        val formattedDuration = DateUtils.formatElapsedTime(((messageContent.audioInfo?.duration ?: 0) / 1000).toLong())
        return if (messageContent.voiceMessageIndicator != null) {
            resources.getString(CommonStrings.voice_message_reply_content, formattedDuration)
        } else {
            resources.getString(CommonStrings.audio_message_reply_content, messageContent.body, formattedDuration)
        }
    }
}
