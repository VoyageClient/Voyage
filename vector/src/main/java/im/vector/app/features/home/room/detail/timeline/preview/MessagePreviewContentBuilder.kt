/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.preview

import android.os.Build
import dagger.Lazy
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.extensions.getVectorLastMessageContent
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.home.room.detail.timeline.factory.PollItemViewStateFactory
import im.vector.app.features.home.room.detail.timeline.format.EventDetailsFormatter
import im.vector.app.features.home.room.detail.timeline.format.NoticeEventFormatter
import im.vector.app.features.home.room.detail.timeline.helper.ForwardedMessagePresenter
import im.vector.app.features.home.room.detail.timeline.helper.MessageSuffixFormatter
import im.vector.app.features.home.room.detail.timeline.helper.PollResponseDataFactory
import im.vector.app.features.home.room.detail.timeline.helper.renderPerMessageProfile
import im.vector.app.features.home.room.detail.timeline.helper.rendersAsDebugMessage
import im.vector.app.features.home.room.detail.timeline.helper.timelineStableId
import im.vector.app.features.home.room.detail.timeline.helper.withoutPerMessageProfileFallback
import im.vector.app.features.home.room.detail.timeline.image.buildImageContentRendererData
import im.vector.app.features.home.room.detail.timeline.item.MediaPillPreview
import im.vector.app.features.home.room.detail.timeline.item.toGalleryTiles
import im.vector.app.features.home.room.detail.timeline.preview.MessagePreviewContent.Media
import im.vector.app.features.home.room.detail.timeline.preview.MessagePreviewContent.TextStyle
import im.vector.app.features.home.room.detail.timeline.render.EventTextRenderer
import im.vector.app.features.home.room.detail.timeline.render.roomMentionSource
import im.vector.app.features.home.room.detail.timeline.tools.linkify
import im.vector.app.features.home.room.detail.timeline.tools.withoutTrailingLineBreak
import im.vector.app.features.html.BodySegment
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.html.HtmlBodySegmenter
import im.vector.app.features.html.PillsPostProcessor
import im.vector.app.features.html.VectorHtmlCompressor
import im.vector.app.features.location.INITIAL_MAP_ZOOM_IN_TIMELINE
import im.vector.app.features.location.STATIC_MAP_PIN_HEIGHT_DP
import im.vector.app.features.location.UrlMapProvider
import im.vector.app.features.location.toLocationData
import im.vector.app.features.pgp.PgpDecryptor
import im.vector.app.features.redaction.preservation.PreservedAttachmentResolver
import im.vector.app.features.redaction.preservation.RedactedContentRestorer
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.translation.MessageTranslationStore
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.room.getTimelineEvent
import org.matrix.android.sdk.api.session.room.model.message.MessageBeaconInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContentWithFormattedBody
import org.matrix.android.sdk.api.session.room.model.message.MessageEndPollContent
import org.matrix.android.sdk.api.session.room.model.message.MessageFormat
import org.matrix.android.sdk.api.session.room.model.message.MessageGalleryContent
import org.matrix.android.sdk.api.session.room.model.message.MessageLocationContent
import org.matrix.android.sdk.api.session.room.model.message.MessagePollContent
import org.matrix.android.sdk.api.session.room.model.message.MessageStickerContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.MessageVerificationRequestContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.model.message.galleryCaption
import org.matrix.android.sdk.api.session.room.model.message.getCaption
import org.matrix.android.sdk.api.session.room.model.message.getFileName
import org.matrix.android.sdk.api.session.room.model.message.getFormattedCaption
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.hasBeenEdited
import org.matrix.android.sdk.api.session.room.timeline.isReply
import org.matrix.android.sdk.api.util.ContentUtils
import org.matrix.android.sdk.api.util.MatrixItem
import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject
import kotlin.math.roundToInt

/** Builds the [MessagePreviewContent] every message preview renders. Safe to call off the main thread. */
class MessagePreviewContentBuilder @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
        private val stringProvider: StringProvider,
        private val vectorPreferences: VectorPreferences,
        private val noticeEventFormatter: NoticeEventFormatter,
        private val htmlRenderer: Lazy<EventHtmlRenderer>,
        private val htmlCompressor: VectorHtmlCompressor,
        private val pillsPostProcessorFactory: PillsPostProcessor.Factory,
        private val textRendererFactory: EventTextRenderer.Factory,
        private val forwardedMessages: ForwardedMessagePresenter,
        private val messageTranslationStore: MessageTranslationStore,
        private val pgpDecryptor: PgpDecryptor,
        private val redactedContentRestorer: RedactedContentRestorer,
        private val preservedAttachmentResolver: PreservedAttachmentResolver,
        private val suffixFormatter: MessageSuffixFormatter,
        private val eventDetailsFormatter: EventDetailsFormatter,
        private val pollItemViewStateFactory: PollItemViewStateFactory,
        private val pollResponseDataFactory: PollResponseDataFactory,
        private val urlMapProvider: UrlMapProvider,
        private val dimensionConverter: DimensionConverter,
) {

    data class Options(
            val thumbnailMaxWidth: Int,
            val thumbnailMaxHeight: Int,
            val galleryMaxWidth: Int,
            val galleryMaxHeight: Int,
            val isThreadTimeline: Boolean = false,
            /** Previews that one item of a gallery as if it had been sent alone. */
            val galleryItemIndex: Int? = null,
            /** Already-decrypted PGP plaintext; otherwise whatever [PgpDecryptor] has cached is used. */
            val pgpPlaintext: CharSequence? = null,
            /** Linkified with inert links; leave off to linkify with a click callback later. */
            val linkify: Boolean = true,
            /** Who an emote is by when it isn't a forward; defaults to the event's sender. */
            val senderItem: MatrixItem? = null,
    )

    /** [text] alone, as a notice, for when the message itself can't be previewed. */
    fun notice(event: TimelineEvent, text: CharSequence): MessagePreviewContent = empty(event).notice(text)

    fun build(rawEvent: TimelineEvent, options: Options): MessagePreviewContent {
        val event = redactedContentRestorer.restoreEvent(rawEvent) ?: rawEvent
        val base = empty(event)
        if (event.root.isRedacted()) {
            return base.copy(text = noticeEventFormatter.formatRedactedEvent(event.root).toEpoxyCharSequence(), textStyle = TextStyle.REDACTED)
        }
        if (event.rendersAsDebugMessage(vectorPreferences.areThreadMessagesEnabled(), options.isThreadTimeline)) {
            return base.notice(noticeEventFormatter.formatDebugOrUnhandled(event.root))
        }

        val rawContent = event.getVectorLastMessageContent()
        val content = (rawContent as? MessageGalleryContent)
                ?.let { gallery -> options.galleryItemIndex?.let { gallery.galleryItems().getOrNull(it) } }
                ?: rawContent
        val profile = event.renderPerMessageProfile(event.senderInfo.disambiguatedDisplayName, vectorPreferences.arePerMessageProfilesEnabled())
        val clearType = event.root.getClearType()

        if (content == null) {
            return base.notice(
                    when {
                        clearType == EventType.MESSAGE || clearType == EventType.STICKER -> noticeEventFormatter.formatMalformedMessage()
                        clearType in EventType.ELEMENT_CALL_NOTIFY.values -> stringProvider.getString(CommonStrings.call_unsupported)
                        else -> noticeEventFormatter.format(event, isDm(event)) ?: noticeEventFormatter.formatDebugOrUnhandled(event.root)
                    }
            )
        }

        val media = buildMedia(event, content, options)
        val withMedia = base.copy(
                media = media,
                attachmentName = (content as? MessageWithAttachmentContent)
                        ?.takeUnless { media is Media.Pill && media.pill is MediaPillPreview.File }
                        ?.getFileName(),
                details = eventDetailsFormatter.format(event, options.galleryItemIndex)?.toString(),
                linkifiable = true,
        )
        // The poll speaks for itself, so it drops the details line naming its kind.
        buildPoll(event, content)?.let { return withMedia.copy(poll = it, details = null) }
        val textRenderer = textRendererFactory.create(event.roomId)
        val pills = pillsPostProcessorFactory.create(event.roomId).forEvent(event.root.originServerTs)
        val translation = messageTranslationStore.get(event)?.takeIf { options.galleryItemIndex == null }
        val pgpPlaintext = if (translation == null) {
            options.pgpPlaintext ?: (content as? MessageContentWithFormattedBody)?.let { pgpDecryptor.peekDecryptedBody(it.body) }
        } else {
            null
        }
        val isReply = content.relatesTo?.inReplyTo?.eventId != null || event.isReply()
        val isNoticeMessage = content.msgType == MessageType.MSGTYPE_NOTICE

        var richSegments: List<BodySegment>? = null
        val text: CharSequence = when {
            translation != null -> {
                val html = translation.formatted?.let { htmlCompressor.compress(it.withoutMxReply()) }
                richSegments = html?.let { richSegmentsOf(it) }
                textRenderer.render(html?.let { htmlRenderer.get().render(it, pills) } ?: translation.text.withoutTrailingLineBreak(), event.roomMentionSource())
            }
            pgpPlaintext != null -> textRenderer.render(pgpPlaintext.withoutTrailingLineBreak(), event.roomMentionSource())
            // Only reached when the poll itself couldn't be found.
            content is MessagePollContent -> stringProvider.getString(CommonStrings.message_reply_to_poll_preview)
            content is MessageEndPollContent -> stringProvider.getString(CommonStrings.message_reply_to_ended_poll_preview)
            content is MessageBeaconInfoContent -> stringProvider.getString(CommonStrings.live_location_description)
            content is MessageVerificationRequestContent -> stringProvider.getString(CommonStrings.verification_request_room_list_preview)
            content.msgType == MessageType.MSGTYPE_LOCATION ->
                return withMedia.copy(
                        // Only shown where the map can't be.
                        text = forwardedMessages.framed(event, noticeEventFormatter.formatLocationNotice(event.root, profile.senderName)).toEpoxyCharSequence(),
                        textStyle = TextStyle.NOTICE,
                        location = buildLocation(content as? MessageLocationContent, options.senderItem ?: event.senderInfo.toMatrixItem()),
                )
            // A sticker's body describes it rather than captioning it.
            content is MessageStickerContent -> ""
            content is MessageWithAttachmentContent -> renderCaption(
                    event,
                    content.getFormattedCaption(isReply),
                    content.getCaption(isReply)?.let { if (isReply) ContentUtils.extractUsefulTextFromReply(it) else it },
                    textRenderer,
                    pills,
            )
            content is MessageGalleryContent -> {
                val caption = content.galleryCaption()?.let { if (isReply) ContentUtils.extractUsefulTextFromReply(it) else it }
                renderCaption(event, content.formattedBody?.takeIf { caption != null }, caption, textRenderer, pills)
            }
            content.rendersHtml() -> {
                val html = (content as MessageContentWithFormattedBody).formattedBody
                        ?.takeIf { it.isNotBlank() }
                        ?.withoutPerMessageProfileFallback(profile.fallbackDisplayName)
                        ?.withoutMxReply()
                        ?.let { htmlCompressor.compress(it) }
                if (html != null) {
                    richSegments = richSegmentsOf(html)
                    textRenderer.render(htmlRenderer.get().render(html, pills), event.roomMentionSource())
                } else {
                    plainBody(content, profile.fallbackDisplayName, isReply).let { textRenderer.render(it.withoutTrailingLineBreak(), event.roomMentionSource()) }
                }
            }
            else -> textRenderer.render(plainBody(content, profile.fallbackDisplayName, isReply).withoutTrailingLineBreak(), event.roomMentionSource())
        }
        val linked = if (options.linkify) text.linkify(null) else text
        val emote = if (content.msgType == MessageType.MSGTYPE_EMOTE) {
            forwardedMessages.emoteBody(event, linked, profile.senderName, options.senderItem ?: event.senderInfo.toMatrixItem())
        } else {
            linked
        }
        // As the timeline marks it: uncaptioned media carries no markers, having no text to put them after.
        val edited = event.hasBeenEdited()
        val shown = if (emote.isEmpty()) emote else suffixFormatter.annotate(emote, edited, translation)
        return withMedia.copy(
                text = (if (media != null) forwardedMessages.framedTail(event, shown) else forwardedMessages.framed(event, shown)).toEpoxyCharSequence(),
                captionlessText = media?.let { forwardedMessages.framedTail(event, "").toEpoxyCharSequence() },
                textStyle = if (isNoticeMessage) TextStyle.NOTICE else TextStyle.NORMAL,
                richSegments = richSegments,
                richFooter = richSegments?.let { suffixFormatter.footer(edited, translation)?.toEpoxyCharSequence() },
                forwardFrame = media?.let { forwardedMessages.previewFrame(event) },
        )
    }

    private fun empty(event: TimelineEvent) = MessagePreviewContent(
            event = event,
            text = null,
            captionlessText = null,
            textStyle = TextStyle.NORMAL,
            linkifiable = false,
            richSegments = null,
            richFooter = null,
            media = null,
            forwardFrame = null,
            attachmentName = null,
            details = null,
            poll = null,
            location = null,
    )

    private fun buildLocation(content: MessageLocationContent?, sender: MatrixItem): MessagePreviewContent.Location? {
        // Static maps are only drawn from Lollipop on, as in the timeline.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        val locationData = content?.toLocationData() ?: return null
        // The pin's tip sits half a pin below the centre of the shown map, in the map image's own pixels.
        val res = dimensionConverter.resources
        val shownSize = res.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.reply_thumbnail_height)
        val tipOffset = (STATIC_MAP_SIZE * STATIC_MAP_PIN_HEIGHT_DP * res.displayMetrics.density / 2 / shownSize).roundToInt()
        return MessagePreviewContent.Location(
                mapUrl = urlMapProvider.buildStaticMapUrl(locationData, INITIAL_MAP_ZOOM_IN_TIMELINE, STATIC_MAP_SIZE, STATIC_MAP_SIZE, tipOffset),
                pinUser = sender.takeIf { content.isSelfLocation() },
        )
    }

    /** A poll as the timeline draws it; an ended poll's event draws the poll it ended. */
    private fun buildPoll(event: TimelineEvent, content: MessageContent): MessagePreviewContent.Poll? {
        val pollEvent = when (content) {
            is MessagePollContent -> event
            is MessageEndPollContent -> content.relatesTo?.eventId?.let { startId ->
                activeSessionHolder.getSafeActiveSession()?.getRoom(event.roomId)?.getTimelineEvent(startId)
            }
            else -> null
        } ?: return null
        val pollContent = pollEvent.getVectorLastMessageContent() as? MessagePollContent ?: return null
        val viewState = pollItemViewStateFactory.create(
                pollContent = pollContent,
                pollResponseData = pollResponseDataFactory.create(event),
                isSent = event.root.sendState.isSent(),
        )
        return MessagePreviewContent.Poll(
                question = suffixFormatter.annotate(viewState.question, pollEvent.hasBeenEdited(), null).toEpoxyCharSequence(),
                options = viewState.optionViewStates.orEmpty(),
                votesStatus = viewState.votesStatus.takeIf { it.isNotEmpty() },
                ended = content is MessageEndPollContent,
        )
    }

    private fun buildMedia(event: TimelineEvent, content: MessageContent, options: Options): Media? {
        if (content is MessageGalleryContent) {
            return Media.Gallery(
                    content.toGalleryTiles(event.eventId, event.timelineStableId(), options.galleryMaxWidth, options.galleryMaxHeight),
                    options.galleryMaxWidth,
            ).takeIf { it.tiles.isNotEmpty() }
        }
        val image = event.buildImageContentRendererData(options.thumbnailMaxWidth, options.thumbnailMaxHeight, options.galleryItemIndex)
        if (image != null) {
            // A redaction purged the server copy; the local one is all that can render.
            val preserved = event.roomId.takeIf { it.isNotEmpty() }?.let { preservedAttachmentResolver.fileFor(it, event.eventId) }
            return Media.Image(image.copy(preservedFile = preserved), isSticker = content is MessageStickerContent)
        }
        return MediaPillPreview.of(content, activeSessionHolder.getSafeActiveSession())?.let { Media.Pill(it) }
    }

    private fun renderCaption(
            event: TimelineEvent,
            formatted: String?,
            plain: String?,
            textRenderer: EventTextRenderer,
            pills: EventHtmlRenderer.PostProcessor,
    ): CharSequence {
        val caption = formatted?.takeIf { it.isNotBlank() }
                ?.let { htmlRenderer.get().render(htmlCompressor.compress(it.withoutMxReply()), pills) }
                ?: plain?.withoutTrailingLineBreak()
                ?: return ""
        return textRenderer.render(caption, event.roomMentionSource())
    }

    private fun plainBody(content: MessageContent, fallbackDisplayName: String?, isReply: Boolean): String {
        val body = content.body.withoutPerMessageProfileFallback(fallbackDisplayName)
        return if (isReply) ContentUtils.extractUsefulTextFromReply(body) else body
    }

    private fun MessageContent.rendersHtml(): Boolean =
            (msgType == MessageType.MSGTYPE_TEXT || msgType == MessageType.MSGTYPE_NOTICE || msgType == MessageType.MSGTYPE_EMOTE) &&
                    this is MessageContentWithFormattedBody && format == MessageFormat.FORMAT_MATRIX_HTML

    private fun richSegmentsOf(html: String): List<BodySegment>? {
        if (!html.contains("<table", ignoreCase = true) && !html.contains("<pre", ignoreCase = true)) return null
        return HtmlBodySegmenter.segment(html).takeIf { segments -> segments.any { it !is BodySegment.Html } }
    }

    private fun isDm(event: TimelineEvent): Boolean =
            activeSessionHolder.getSafeActiveSession()?.getRoomSummary(event.roomId)?.isDirect == true

    private fun MessagePreviewContent.notice(text: CharSequence) =
            copy(text = text.toEpoxyCharSequence(), textStyle = TextStyle.NOTICE, linkifiable = false)

    private companion object {
        // Previews draw the map as a small square; this leaves room for high-density screens.
        const val STATIC_MAP_SIZE = 512
    }
}

/** Drops every legacy `<mx-reply>` block, nested ones included: a quoted message never shows its own quote. */
private fun String.withoutMxReply(): String {
    val start = indexOf(MX_REPLY_OPEN, ignoreCase = true)
    if (start == -1) return this
    val end = lastIndexOf(MX_REPLY_CLOSE, ignoreCase = true)
    if (end < start) return this
    return (substring(0, start) + substring(end + MX_REPLY_CLOSE.length)).trim()
}

private const val MX_REPLY_OPEN = "<mx-reply>"
private const val MX_REPLY_CLOSE = "</mx-reply>"
