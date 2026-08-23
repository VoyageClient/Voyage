/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.content.Context
import android.net.Uri
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.format.DateUtils
import android.util.AttributeSet
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.text.toSpannable
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.extensions.clearDrawables
import im.vector.app.core.extensions.getVectorLastMessageContent
import im.vector.app.core.extensions.setRedactedPreviewStyle
import im.vector.app.core.extensions.setTextIfDifferent
import im.vector.app.core.extensions.showKeyboard
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.platform.SimpleTextWatcher
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.core.utils.nonScrollingLinkMovementMethod
import im.vector.app.databinding.ComposerLayoutBinding
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.emoji.TwemojiProvider
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.format.NoticeEventFormatter
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.app.features.home.room.detail.timeline.helper.timelineStableId
import im.vector.app.features.home.room.detail.timeline.image.buildImageContentRendererData
import im.vector.app.features.home.room.detail.timeline.item.GalleryGridBinder
import im.vector.app.features.home.room.detail.timeline.item.toGalleryTiles
import im.vector.app.features.home.room.detail.timeline.render.RichMessageBodyRenderer
import im.vector.app.features.home.room.detail.timeline.style.mediaPreviewCornerRadiusPx
import im.vector.app.features.home.room.detail.timeline.tools.asEmoteBody
import im.vector.app.features.home.room.detail.timeline.tools.attachmentPreviewText
import im.vector.app.features.home.room.detail.timeline.tools.linkify
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.html.BodySegment
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.html.HtmlBodySegmenter
import im.vector.app.features.html.PillImageSpan
import im.vector.app.features.html.PillsPostProcessor
import im.vector.app.features.html.VectorHtmlCompressor
import im.vector.app.features.html.bindPillImageSpans
import im.vector.app.features.html.expandPillSpans
import im.vector.app.features.html.setPillSpan
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.MediaContentRevealManager
import im.vector.app.features.media.shouldHideMediaPreview
import im.vector.app.features.redaction.preservation.RedactedContentRestorer
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.text.DirectionOverridesTransformation
import im.vector.lib.strings.CommonStrings
import org.commonmark.parser.Parser
import org.matrix.android.sdk.api.session.crypto.model.RoomEncryptionTrustLevel
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.getUserOrDefault
import org.matrix.android.sdk.api.session.permalinks.PermalinkData
import org.matrix.android.sdk.api.session.permalinks.PermalinkParser
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageBeaconInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContentWithFormattedBody
import org.matrix.android.sdk.api.session.room.model.message.MessageEndPollContent
import org.matrix.android.sdk.api.session.room.model.message.MessageFileContent
import org.matrix.android.sdk.api.session.room.model.message.MessageFormat
import org.matrix.android.sdk.api.session.room.model.message.MessageGalleryContent
import org.matrix.android.sdk.api.session.room.model.message.MessagePollContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.model.message.galleryCaption
import org.matrix.android.sdk.api.session.room.model.message.getCaption
import org.matrix.android.sdk.api.session.room.model.message.getFileName
import org.matrix.android.sdk.api.session.room.send.MatrixItemSpan
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.util.ContentUtils
import org.matrix.android.sdk.api.util.MatrixItem
import org.matrix.android.sdk.api.util.toMatrixItem
import org.matrix.android.sdk.api.util.toMatrixItemOrNull
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
    @Inject lateinit var textRendererFactory: im.vector.app.features.home.room.detail.timeline.render.EventTextRenderer.Factory
    @Inject lateinit var activeSessionHolder: ActiveSessionHolder
    @Inject lateinit var redactedContentRestorer: RedactedContentRestorer
    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var mediaContentRevealManager: MediaContentRevealManager
    @Inject lateinit var pgpDecryptor: im.vector.app.features.pgp.PgpDecryptor
    @Inject lateinit var messageTranslationStore: im.vector.app.features.translation.MessageTranslationStore
    @Inject lateinit var twemojiProvider: TwemojiProvider

    private val views: ComposerLayoutBinding

    private val classic = vectorPreferences.useClassicComposer()

    // The replied-to/related event currently shown in the preview, so its media can be re-rendered
    // in place when revealed elsewhere.
    private var relatedMessageEvent: TimelineEvent? = null

    // Lets a reconstructed pill resolve its member (and so its room display name) the way the timeline does.
    var roomId: String? = null

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
        inflate(context, if (classic) R.layout.composer_layout_classic else R.layout.composer_layout, this)
        views = ComposerLayoutBinding.bind(this)

        views.composerEditText.maxLines = MessageComposerView.MAX_LINES_WHEN_COLLAPSED
        // Draw direction-override chars (e.g. in an edited message) as tofu instead of letting them
        // flip the field; the Editable and the sent text keep the real characters.
        views.composerEditText.transformationMethod = DirectionOverridesTransformation

        // Must precede any text: Markwon only installs its own (scrolling) LinkMovementMethod when the view has none.
        views.composerRelatedMessageContent.movementMethod = nonScrollingLinkMovementMethod

        // Round the replied-to image corners. Glide's RoundedCorners only transforms the loaded
        // bitmap, so a still-loading blurhash placeholder (Drawable) would otherwise show square.
        views.composerRelatedMessageImage.setCornerRadius(mediaPreviewCornerRadiusPx(context).toFloat())

        collapse(animate = false)

        // Render emoji the user types/pastes as Twemoji sprites in the input box (the platform/emoji2
        // can't on ICS or when Twemoji is forced). setSpan doesn't retrigger text watchers, so no loop.
        if (twemojiProvider.enabled) {
            views.composerEditText.addTextChangedListener(object : SimpleTextWatcher() {
                override fun afterTextChanged(s: Editable) {
                    twemojiProvider.applyTo(s)
                }
            })
        }

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

    private var lastSpecialModeKey: Pair<String, String>? = null

    private fun collapse(animate: Boolean = true, transitionComplete: (() -> Unit)? = null) {
        lastSpecialModeKey = null
        if (animate) beginClassicTransition()
        views.relatedMessageGroup.isVisible = false
        views.composerTopDivider.isVisible = classic
        transitionComplete?.invoke()
        callback?.onExpandOrCompactChange()
    }

    private fun expand(animate: Boolean = true, transitionComplete: (() -> Unit)? = null) {
        if (animate) beginClassicTransition()
        views.relatedMessageGroup.isVisible = true
        // The preview brings its own top separator.
        views.composerTopDivider.isVisible = false
        transitionComplete?.invoke()
        callback?.onExpandOrCompactChange()
    }

    private fun beginClassicTransition() {
        if (!classic || !ViewCompat.isAttachedToWindow(this)) return
        val transition = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_SEQUENTIAL
            addTransition(ChangeBounds())
            addTransition(Fade(Fade.IN))
            duration = RELATED_MESSAGE_ANIMATION_DURATION
            // Target the preview only; untargeted, this also animates the input row.
            addTarget(views.relatedMessageGroup)
        }
        TransitionManager.beginDelayedTransition(this, transition)
    }

    override fun setTextIfDifferent(text: CharSequence?): Boolean {
        return views.composerEditText.setTextIfDifferent(text)
    }

    private fun renderRelatedMessageGallery(event: TimelineEvent): Boolean {
        val gallery = event.getVectorLastMessageContent() as? MessageGalleryContent ?: run {
            GalleryGridBinder.unbind(views.composerRelatedMessageGallery, imageContentRenderer)
            return false
        }
        val session = activeSessionHolder.getSafeActiveSession()
        val maxWidth = dimensionConverter.dpToPx(160)
        val items = gallery.galleryItems()
        GalleryGridBinder.bind(
                grid = views.composerRelatedMessageGallery,
                tiles = gallery.toGalleryTiles(event.eventId, event.timelineStableId(), maxWidth, maxWidth, items = items),
                maxWidth = maxWidth,
                cornerRadiusPx = dimensionConverter.dpToPx(8),
                imageContentRenderer = imageContentRenderer,
                hideMedia = session != null && shouldHideMediaPreview(event, session, vectorPreferences, mediaContentRevealManager),
                hideMediaSolidColor = vectorPreferences.useSolidColorForHiddenMedia(),
        )
        return true
    }

    private fun renderRelatedMessageImage(event: TimelineEvent, crossFade: Boolean = false): Boolean {
        val data = event.buildImageContentRendererData(dimensionConverter.dpToPx(66))
        return if (data != null) {
            val session = activeSessionHolder.getSafeActiveSession()
            val hidden = session != null && shouldHideMediaPreview(event, session, vectorPreferences, mediaContentRevealManager)
            // Full image for transparent-capable content (server thumbnails can bake in a background).
            val mode = ImageContentRenderer.previewMode(isSticker = false, mimeType = data.mimeType)
            if (hidden) {
                imageContentRenderer.renderHidden(data, mode, views.composerRelatedMessageImage, vectorPreferences.useSolidColorForHiddenMedia())
            } else {
                // The view rounds the picture; a bitmap-baked radius would scale with the decode size.
                imageContentRenderer.render(data, mode, views.composerRelatedMessageImage, cornerTransformation = null, crossFade = crossFade)
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
        if (views.composerRelatedMessageGallery.isVisible) {
            renderRelatedMessageGallery(event)
            return
        }
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
                // The backing text is a placeholder char, so take the label from the span's body text —
                // never the item's name, which may carry a local display-name override.
                append("[").append((span as? PillImageSpan)?.bodyText ?: span.matrixItem.getBestName()).append("]")
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
                // Prefer the room member: only it carries the name (and any local override) the pill
                // should draw. The global user is a fallback that often knows nothing but the id.
                is PermalinkData.UserLink ->
                    roomId?.let { session.roomService().getRoomMember(data.userId, it)?.toMatrixItem() }
                            ?: session.getUserOrDefault(data.userId).toMatrixItem()
                is PermalinkData.RoomLink -> session.getRoomSummary(data.roomIdOrAlias)?.toMatrixItem()
                else -> null
            }
            if (matrixItem != null) {
                val start = out.length
                out.append(label)
                val span = PillImageSpan(GlideApp.with(this), avatarRenderer, context, matrixItem, bodyText = label)
                        .also { it.bind(editText) }
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
                // ic_composer_rich_text_save bakes in its own filled disc, which the classic composer's flat
                // accent tint would flood into a solid accent circle; there the glyph has to stand alone.
                setImageResource(if (classic) R.drawable.ic_check_on else R.drawable.ic_composer_rich_text_save)
            } else {
                contentDescription = resources.getString(CommonStrings.action_send)
                setImageResource(if (classic) R.drawable.ic_send else R.drawable.ic_rich_composer_send)
            }
        }
    }

    private fun renderSpecialMode(specialMode: MessageComposerMode.Special) {
        // Re-rendering the same target (it was edited or redacted under the composer) must not replay the
        // expand animation or pull the keyboard back up.
        val modeKey = specialMode.javaClass.name to specialMode.event.eventId
        val isRefresh = views.relatedMessageGroup.isVisible && lastSpecialModeKey == modeKey
        lastSpecialModeKey = modeKey
        // A revealed redaction previews its restored content here too, matching the timeline.
        val restored = redactedContentRestorer.restoreEvent(specialMode.event)
        val event = restored ?: specialMode.event
        val defaultContent = specialMode.defaultContent

        val surfaceColor = ThemeUtils.getColor(context, com.google.android.material.R.attr.colorSurface)
        views.relatedMessageBackground.setBackgroundColor(
                // Only recovered content is marked; the bare "Message removed" placeholder isn't.
                if (restored != null) {
                    // Composited, not stacked: the strip sits on the opaque composer surface.
                    ColorUtils.compositeColors(ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_redacted_background), surfaceColor)
                } else {
                    surfaceColor
                }
        )

        val iconRes: Int = when (specialMode) {
            is MessageComposerMode.Reply -> R.drawable.ic_reply
            is MessageComposerMode.Edit -> R.drawable.ic_edit
            is MessageComposerMode.Quote -> R.drawable.ic_quote
        }

        val pillsPostProcessor = pillsPostProcessorFactory.create(event.roomId)
        val textRenderer = textRendererFactory.create(event.roomId)

        // switch to expanded bar
        views.composerRelatedMessageTitle.apply {
            text = event.senderInfo.disambiguatedDisplayName.prepareForDisplay()
            setTextColor(matrixItemColorProvider.getColor(event.senderInfo.toMatrixItemOrNull() ?: MatrixItem.UserItem("@")))
        }

        val messageContent: MessageContent? = event.getVectorLastMessageContent()
        // Translation / PGP: show the text the timeline shows for the quoted message (and skip HTML
        // rendering of the real formatted_body below).
        val pgpPlain = messageTranslationStore.get(event.eventId)?.text
                ?: (messageContent as? MessageContentWithFormattedBody)?.let { pgpDecryptor.peekDecryptedBody(it.body) }
        val nonFormattedBody = when {
            pgpPlain != null -> pgpPlain
            event.root.isRedacted() -> noticeEventFormatter.formatRedactedEvent(event.root)
            messageContent is MessageFileContent -> attachmentPreviewText(context, R.drawable.ic_paperclip, messageContent.getFileName().orEmpty())
            messageContent is MessageAudioContent -> {
                val icon = if (messageContent.voiceMessageIndicator != null) R.drawable.ic_microphone else R.drawable.ic_music_note
                attachmentPreviewText(context, icon, getAudioContentBodyText(messageContent))
            }
            // The grid preview carries the content, so the text slot is caption-only.
            messageContent is MessageGalleryContent -> messageContent.galleryCaption().orEmpty()
            messageContent is MessagePollContent -> messageContent.getBestPollCreationInfo()?.question?.getBestQuestion()
            messageContent is MessageBeaconInfoContent -> resources.getString(CommonStrings.live_location_description)
            messageContent is MessageEndPollContent -> resources.getString(CommonStrings.message_reply_to_ended_poll_preview)
            // The composer preview never shows a map, so location is always the notice text.
            messageContent?.msgType == MessageType.MSGTYPE_LOCATION ->
                noticeEventFormatter.formatLocationNotice(event.root, event.senderInfo.disambiguatedDisplayName)
            // A message whose content can't be parsed previews as the timeline's malformed placeholder.
            messageContent == null && event.root.getClearType() in listOf(EventType.MESSAGE, EventType.STICKER) ->
                noticeEventFormatter.formatMalformedMessage()
            // Non-message event (membership change, reaction, …): show the notice text, falling back
            // to a debug line (known type) or the accent "not handled" notice (unknown type).
            messageContent == null -> noticeEventFormatter.format(event, isDm = false)
                    ?: noticeEventFormatter.formatDebugOrUnhandled(event.root)
            // Text / notice / emote without a formatted body: drop the legacy "> <@user:server> …"
            // reply fallback, which the formatted path below strips via <mx-reply>.
            messageContent.relatesTo?.inReplyTo?.eventId != null ->
                ContentUtils.extractUsefulTextFromReply(messageContent.body, (messageContent as? MessageContentWithFormattedBody)?.matrixFormattedBody)
            else -> messageContent.body
        }
        var formattedBody: CharSequence? = null
        var renderedTable = false
        // m.text, m.notice and m.emote all carry a formatted_body; render it so HTML-only bodies
        // (common for bot m.notice messages) aren't shown blank or as raw markup.
        val isFormattableText = messageContent?.msgType == MessageType.MSGTYPE_TEXT ||
                messageContent?.msgType == MessageType.MSGTYPE_NOTICE ||
                messageContent?.msgType == MessageType.MSGTYPE_EMOTE
        if (pgpPlain == null && isFormattableText && messageContent is MessageContentWithFormattedBody &&
                messageContent.format == MessageFormat.FORMAT_MATRIX_HTML) {
            val htmlToRender = messageContent.formattedBody?.let { ContentUtils.extractUsefulTextFromHtmlReply(it) }
            val compressed = htmlToRender?.let { htmlCompressor.compress(it) }
            val richSegments = if (compressed != null && (compressed.contains("<table", ignoreCase = true) || compressed.contains("<pre", ignoreCase = true))) {
                HtmlBodySegmenter.segment(compressed).takeIf { segs -> segs.any { it !is BodySegment.Html } }
            } else {
                null
            }
            if (richSegments != null) {
                renderRichPreview(richSegments, pillsPostProcessor)
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
        // Resolve mentions/permalinks (incl. message links -> "Message in Room") into pills for the
        // preview only; the un-pilled [formattedBody] still feeds the edit box below. Linkified like the
        // timeline's reply header, or a bare URL in a plaintext body carries no span and renders as
        // ordinary text here while showing blue everywhere else.
        // An uncaptioned attachment previews as its filename, and a name like "Screenshot-…@2x.png" reads as an e-mail address.
        val isFilenamePreview = messageContent is MessageWithAttachmentContent && messageContent.getCaption() == null
        val renderedBody = (formattedBody ?: nonFormattedBody)?.let { textRenderer.render(it) }
                ?.let { if (isFilenamePreview) it else it.linkify(null) }
        val previewBody = if (renderedBody != null && !event.root.isRedacted() && messageContent?.msgType == MessageType.MSGTYPE_EMOTE) {
            renderedBody.asEmoteBody(event.senderInfo.disambiguatedDisplayName)
        } else {
            renderedBody
        }
        eventHtmlRenderer.setTextWithPlugins(views.composerRelatedMessageContent, previewBody?.prepareForDisplay())
        // Without this the preview's pills only ever show what the synchronous avatar lookup found, so
        // a re-render that misses the cache leaves them stuck on the placeholder.
        views.composerRelatedMessageContent.bindPillImageSpans()
        // Markwon's CorePlugin.afterSetText installs a LinkMovementMethod when the view has none; the
        // preview's links stay inert, as they are in the reply header.
        views.composerRelatedMessageContent.movementMethod = null
        // Muted grey for non-message notices and m.notice messages (which render grey in the
        // timeline), normal text colour for everything else.
        val contentColorAttr = if (messageContent == null || messageContent.msgType == MessageType.MSGTYPE_NOTICE) {
            im.vector.lib.ui.styles.R.attr.vctr_content_secondary
        } else {
            im.vector.lib.ui.styles.R.attr.vctr_message_text_color
        }
        views.composerRelatedMessageContent.setTextColor(ThemeUtils.getColor(context, contentColorAttr))
        if (event.root.isRedacted()) {
            views.composerRelatedMessageContent.setRedactedPreviewStyle()
        } else {
            views.composerRelatedMessageContent.clearDrawables()
        }

        // Image Event
        relatedMessageEvent = event
        val isGalleryVisible = renderRelatedMessageGallery(event)
        views.composerRelatedMessageGallery.isVisible = isGalleryVisible
        val isImageVisible = !isGalleryVisible && renderRelatedMessageImage(event)
        views.composerRelatedMessageImage.isVisible = isImageVisible

        views.composerRelatedMessageActionIcon.setImageDrawable(ContextCompat.getDrawable(context, iconRes))

        avatarRenderer.render(event.senderInfo.toMatrixItem(), views.composerRelatedMessageAvatar)

        val content = if (specialMode is MessageComposerMode.Edit) {
            // Edit against the plain body — the markdown source the user typed. The rendered
            // formatted body can't roundtrip: list/quote markers exist only as spans, so saving
            // would flatten the structure.
            reconstructMentionPills(defaultContent)
        } else {
            defaultContent
        }

        // On a refresh the box is the source of truth: the state's text is a snapshot that can trail the
        // user's typing, and setText() would drop those keystrokes and send the cursor to the start.
        if (!isRefresh && views.composerEditText.text?.toString() != content.toString()) {
            views.composerEditText.setText(content)
        }

        expand(animate = !isRefresh) {
            // need to do it here also when not using quick reply
            if (isVisible && !isRefresh) {
                // Post so the focus request runs after the RecyclerView's swipe-gesture
                // touch processing settles; targeting the EditText directly (not the parent
                // layout) ensures the IME input connection is fully established.
                views.composerEditText.post {
                    views.composerEditText.showKeyboard(andRequestFocus = true)
                }
            }
            views.composerRelatedMessageImage.isVisible = isImageVisible
        }
    }

    // Render a table/code-containing reply/edit preview via the timeline's renderer, instead of the
    // single-line TextView that collapses table cells to plaintext and wraps code (losing scroll +
    // line numbers).
    private fun renderRichPreview(segments: List<BodySegment>, pillsPostProcessor: PillsPostProcessor) {
        views.composerRelatedMessageRichContainer.isVisible = true
        richMessageBodyRenderer.render(
                container = views.composerRelatedMessageRichContainer,
                segments = segments,
                postProcessors = arrayOf(pillsPostProcessor),
                movementMethod = null,
                onClick = {},
                onLongClick = { false },
                interactive = false,
        )
    }

    override fun renderRoomEncryption(isEncrypted: Boolean, trustLevel: RoomEncryptionTrustLevel?, isPgp: Boolean) {
        if (!classic) return
        if (isEncrypted || isPgp) {
            views.composerShield.renderRoomShield(trustLevel, isPgp)
        } else {
            views.composerShield.isVisible = false
        }
    }

    private fun getAudioContentBodyText(messageContent: MessageAudioContent): String {
        val formattedDuration = DateUtils.formatElapsedTime(((messageContent.audioInfo?.duration ?: 0) / 1000).toLong())
        return if (messageContent.voiceMessageIndicator != null) {
            resources.getString(CommonStrings.voice_message_reply_content, formattedDuration)
        } else {
            resources.getString(CommonStrings.audio_message_reply_content, messageContent.body, formattedDuration)
        }
    }

    companion object {
        private const val RELATED_MESSAGE_ANIMATION_DURATION = 100L
    }
}
