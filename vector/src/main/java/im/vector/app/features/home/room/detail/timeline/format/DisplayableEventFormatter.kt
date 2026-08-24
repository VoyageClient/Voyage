/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.format

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.URLSpan
import dagger.Lazy
import im.vector.app.R
import im.vector.app.core.extensions.getVectorLastMessageContent
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.DrawableProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.home.room.detail.timeline.tools.messageEmojiSpanify
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.html.PillImageSpan
import im.vector.app.features.pgp.PgpDecryptor
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.text.neutralizeDirectionOverrides
import im.vector.lib.strings.CommonStrings
import me.gujun.android.span.span
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContentWithFormattedBody
import org.matrix.android.sdk.api.session.room.model.message.MessagePollContent
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.relation.ReactionContent
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.util.ContentUtils
import javax.inject.Inject

class DisplayableEventFormatter @Inject constructor(
        private val stringProvider: StringProvider,
        private val colorProvider: ColorProvider,
        private val drawableProvider: DrawableProvider,
        private val noticeEventFormatter: NoticeEventFormatter,
        private val reactionFormatter: ReactionFormatter,
        private val htmlRenderer: Lazy<EventHtmlRenderer>,
        private val pgpDecryptor: PgpDecryptor,
        private val messageTranslationStore: im.vector.app.features.translation.MessageTranslationStore,
        private val pillsPostProcessorFactory: im.vector.app.features.html.PillsPostProcessor.Factory,
        private val textRendererFactory: im.vector.app.features.home.room.detail.timeline.render.EventTextRenderer.Factory,
) {

    // AUTOLINK_WEB_URL, not WEB_URL: its (?<!://) lookbehind is what stops the host part of a
    // non-web URI (mxc://server/id) from matching, mirroring the timeline's LinkifyCompat behavior.
    private val webUrlRegex = androidx.core.util.PatternsCompat.AUTOLINK_WEB_URL.toRegex()

    // Rendered previews, keyed by room + source HTML. The room list re-renders every visible summary
    // on each update, and parsing the same HTML again cost hundreds of ms on the main thread.
    private val previewCache = android.util.LruCache<String, CharSequence>(256)

    // textColorLink is stamped into the spans at render time, so a theme change invalidates the cache.
    private var previewCacheThemeGeneration = ThemeUtils.themeGeneration

    // Per-room pill processors, cached so the room list doesn't rebuild them on every summary render.
    private val pillProcessors = java.util.concurrent.ConcurrentHashMap<String, Pair<im.vector.app.features.html.PillsPostProcessor, im.vector.app.features.home.room.detail.timeline.render.EventTextRenderer>>()

    private fun pillProcessorsFor(roomId: String) = pillProcessors.getOrPut(roomId) {
        pillsPostProcessorFactory.create(roomId) to textRendererFactory.create(roomId)
    }

    private fun formatReaction(roomId: String?, key: String, reactionSenderId: String?): CharSequence =
            reactionFormatter.format(CommonStrings.sent_a_reaction, roomId, key, reactionSenderId)

    fun format(timelineEvent: TimelineEvent, isDm: Boolean, appendAuthor: Boolean, unhandledFallback: Boolean = false): CharSequence {
        if (timelineEvent.root.isRedacted()) {
            return noticeEventFormatter.formatRedactedEvent(timelineEvent.root)
        }

        if (timelineEvent.root.isEncrypted() &&
                timelineEvent.root.mxDecryptionResult == null) {
            return stringProvider.getString(CommonStrings.encrypted_message_room_list_preview)
        }

        val senderName = timelineEvent.senderInfo.disambiguatedDisplayName

        return when (timelineEvent.root.getClearType()) {
            EventType.MESSAGE -> {
                timelineEvent.getVectorLastMessageContent()?.let { messageContent ->
                    val translation = messageTranslationStore.get(timelineEvent.eventId)
                    if (translation != null) {
                        return@let simpleFormat(senderName, translation.text, appendAuthor)
                    }
                    val pgp = (messageContent as? MessageTextContent)?.let { pgpDecryptor.peekDecryptedBody(it.body) }
                    if (pgp != null) {
                        return@let simpleFormat(senderName, pgp, appendAuthor)
                    }
                    when (messageContent.msgType) {
                        MessageType.MSGTYPE_TEXT,
                        MessageType.MSGTYPE_NOTICE -> {
                            val preview = messageContent.previewText()
                            if (preview.formattedBody != null) {
                                // Render the formatted HTML so custom emotes and inline colours survive.
                                simpleFormat(senderName, renderFormattedPreview(timelineEvent.root.roomId, preview.formattedBody), appendAuthor)
                            } else {
                                simpleFormat(senderName, renderPlainPreview(timelineEvent.root.roomId, preview.body), appendAuthor)
                            }
                        }
                        MessageType.MSGTYPE_EMOTE -> {
                            val preview = messageContent.previewText()
                            val rendered = if (preview.formattedBody != null) {
                                renderFormattedPreview(timelineEvent.root.roomId, preview.formattedBody)
                            } else {
                                renderPlainPreview(timelineEvent.root.roomId, preview.body)
                            }
                            simpleFormat(senderName, rendered, appendAuthor, isEmote = true)
                        }
                        MessageType.MSGTYPE_VERIFICATION_REQUEST -> {
                            simpleFormat(senderName, stringProvider.getString(CommonStrings.verification_request_room_list_preview), appendAuthor)
                        }
                        MessageType.MSGTYPE_IMAGE -> {
                            simpleFormat(senderName, stringProvider.getString(CommonStrings.sent_an_image), appendAuthor)
                        }
                        MessageType.MSGTYPE_AUDIO -> {
                            when {
                                (messageContent as? MessageAudioContent)?.voiceMessageIndicator == null -> {
                                    simpleFormat(senderName, stringProvider.getString(CommonStrings.sent_an_audio_file), appendAuthor)
                                }
                                else -> {
                                    simpleFormat(senderName, stringProvider.getString(CommonStrings.sent_a_voice_message), appendAuthor)
                                }
                            }
                        }
                        MessageType.MSGTYPE_VIDEO -> {
                            simpleFormat(senderName, stringProvider.getString(CommonStrings.sent_a_video), appendAuthor)
                        }
                        MessageType.MSGTYPE_FILE -> {
                            simpleFormat(senderName, stringProvider.getString(CommonStrings.sent_a_file), appendAuthor)
                        }
                        MessageType.MSGTYPE_GALLERY,
                        MessageType.MSGTYPE_GALLERY_STABLE -> {
                            simpleFormat(senderName, stringProvider.getString(CommonStrings.sent_a_gallery), appendAuthor)
                        }
                        MessageType.MSGTYPE_LOCATION -> {
                            simpleFormat(senderName, stringProvider.getString(CommonStrings.location_room_list_preview), appendAuthor)
                        }
                        else -> {
                            simpleFormat(senderName, messageContent.body, appendAuthor)
                        }
                    }
                } ?: simpleFormat(senderName, noticeEventFormatter.formatMalformedMessage(), appendAuthor)
            }
            EventType.STICKER -> {
                simpleFormat(senderName, stringProvider.getString(CommonStrings.send_a_sticker), appendAuthor)
            }
            EventType.REACTION -> {
                timelineEvent.root.getClearContent().toModel<ReactionContent>()?.relatesTo?.let {
                    simpleFormat(senderName, formatReaction(timelineEvent.root.roomId, it.key, timelineEvent.root.senderId), appendAuthor)
                } ?: span { }
            }
            EventType.KEY_VERIFICATION_CANCEL,
            EventType.KEY_VERIFICATION_DONE -> {
                // cancel and done can appear in timeline, so should have representation
                simpleFormat(senderName, stringProvider.getString(CommonStrings.sent_verification_conclusion), appendAuthor)
            }
            EventType.KEY_VERIFICATION_START,
            EventType.KEY_VERIFICATION_ACCEPT,
            EventType.KEY_VERIFICATION_MAC,
            EventType.KEY_VERIFICATION_KEY,
            EventType.KEY_VERIFICATION_READY,
            EventType.CALL_CANDIDATES -> {
                span { }
            }
            in EventType.POLL_START.values -> {
                (timelineEvent.getVectorLastMessageContent() as? MessagePollContent)?.getBestPollCreationInfo()?.question?.getBestQuestion()
                        ?: stringProvider.getString(CommonStrings.sent_a_poll)
            }
            in EventType.POLL_RESPONSE.values -> {
                stringProvider.getString(CommonStrings.poll_response_room_list_preview)
            }
            in EventType.POLL_END.values -> {
                stringProvider.getString(CommonStrings.poll_end_room_list_preview)
            }
            in EventType.STATE_ROOM_BEACON_INFO.values -> {
                simpleFormat(senderName, stringProvider.getString(CommonStrings.live_location_room_list_preview), appendAuthor)
            }
            in EventType.ELEMENT_CALL_NOTIFY.values -> {
                simpleFormat(senderName, stringProvider.getString(CommonStrings.call_unsupported), appendAuthor)
            }
            else -> {
                val formatted = noticeEventFormatter.format(timelineEvent, isDm)
                when {
                    // Not span{} — it wouldn't preserve the emoji ReplacementSpans (names in "X joined" etc.).
                    formatted != null -> formatted.prepareForDisplay()
                    // Reply previews want unhandled/debug events to read as they do in the timeline,
                    // rather than collapsing to an empty header; other callers keep the blank fallback.
                    unhandledFallback -> noticeEventFormatter.formatDebugOrUnhandled(timelineEvent.root)
                    else -> span { }
                }
            }
        }
    }

    fun formatThreadSummary(
            event: Event?,
            latestEdition: String? = null
    ): CharSequence {
        event ?: return ""

        // There event have been edited
        if (latestEdition != null) {
            return renderFormattedPreview(event.roomId, latestEdition)
        }

        // The event have been redacted
        if (event.isRedacted()) {
            return noticeEventFormatter.formatRedactedEvent(event)
        }

        // The event is encrypted
        if (event.isEncrypted() &&
                event.mxDecryptionResult == null) {
            return stringProvider.getString(CommonStrings.encrypted_message_room_list_preview)
        }

        return when (event.getClearType()) {
            EventType.MESSAGE -> {
                (event.getClearContent().toModel() as? MessageContent)?.let { messageContent ->
                    when (messageContent.msgType) {
                        MessageType.MSGTYPE_TEXT,
                        MessageType.MSGTYPE_NOTICE -> {
                            val preview = messageContent.previewText()
                            if (preview.formattedBody != null) {
                                renderFormattedPreview(event.roomId, preview.formattedBody)
                            } else {
                                renderPlainPreview(event.roomId, preview.body)
                            }
                        }
                        MessageType.MSGTYPE_VERIFICATION_REQUEST -> {
                            stringProvider.getString(CommonStrings.verification_request_room_list_preview)
                        }
                        MessageType.MSGTYPE_IMAGE -> {
                            stringProvider.getString(CommonStrings.sent_an_image)
                        }
                        MessageType.MSGTYPE_AUDIO -> {
                            if ((messageContent as? MessageAudioContent)?.voiceMessageIndicator != null) {
                                stringProvider.getString(CommonStrings.sent_a_voice_message)
                            } else {
                                stringProvider.getString(CommonStrings.sent_an_audio_file)
                            }
                        }
                        MessageType.MSGTYPE_VIDEO -> {
                            stringProvider.getString(CommonStrings.sent_a_video)
                        }
                        MessageType.MSGTYPE_FILE -> {
                            stringProvider.getString(CommonStrings.sent_a_file)
                        }
                        MessageType.MSGTYPE_GALLERY,
                        MessageType.MSGTYPE_GALLERY_STABLE -> {
                            stringProvider.getString(CommonStrings.sent_a_gallery)
                        }
                        MessageType.MSGTYPE_LOCATION -> {
                            stringProvider.getString(CommonStrings.location_room_list_preview)
                        }
                        else -> {
                            messageContent.body
                        }
                    }
                } ?: noticeEventFormatter.formatMalformedMessage()
            }
            EventType.STICKER -> {
                stringProvider.getString(CommonStrings.send_a_sticker)
            }
            EventType.REACTION -> {
                event.getClearContent().toModel<ReactionContent>()?.relatesTo?.let {
                    formatReaction(event.roomId, it.key, event.senderId)
                } ?: span { }
            }
            in EventType.POLL_START.values -> {
                event.getClearContent().toModel<MessagePollContent>(catchError = true)?.pollCreationInfo?.question?.question
                        ?: stringProvider.getString(CommonStrings.sent_a_poll)
            }
            in EventType.POLL_RESPONSE.values -> {
                stringProvider.getString(CommonStrings.poll_response_room_list_preview)
            }
            in EventType.POLL_END.values -> {
                stringProvider.getString(CommonStrings.poll_end_room_list_preview)
            }
            in EventType.STATE_ROOM_BEACON_INFO.values -> {
                stringProvider.getString(CommonStrings.live_location_room_list_preview)
            }
            in EventType.ELEMENT_CALL_NOTIFY.values -> {
                stringProvider.getString(CommonStrings.call_unsupported)
            }
            else -> {
                span {
                }
            }
        }
    }

    private class PreviewText(val formattedBody: String?, val body: String)

    // Strip the reply fallback so the preview shows the reply's own content, not the quoted message: the
    // <mx-reply> block from the formatted body, or the legacy "> <@user>" prefix from the plain body.
    private fun MessageContent.previewText(): PreviewText {
        val isReply = relatesTo?.inReplyTo?.eventId != null
        val formattedBody = (this as? MessageContentWithFormattedBody)?.matrixFormattedBody?.takeIf { it.isNotBlank() }
                ?.let { ContentUtils.extractUsefulTextFromHtmlReply(it) }
        val previewBody = if (isReply) ContentUtils.extractUsefulTextFromReply(body) else body
        return PreviewText(formattedBody, previewBody)
    }

    // Render a formatted preview the way the timeline does: mentions/rooms become pills (pillsPostProcessor),
    // matrix.to message links become "Message in Room" pills (EventTextRenderer), then bare links get coloured.
    private fun renderFormattedPreview(roomId: String?, formattedBody: String): CharSequence {
        val generation = ThemeUtils.themeGeneration
        if (generation != previewCacheThemeGeneration) {
            previewCacheThemeGeneration = generation
            previewCache.evictAll()
        }
        val key = "${roomId.orEmpty()}\u0000$formattedBody"
        // Copy on the way out: callers run the result through EmojiCompat.process(), which spans a
        // Spannable in place, so handing out the cached instance would mutate it from several threads.
        previewCache.get(key)?.let { return SpannableStringBuilder(it) }
        // colorBareLinks must run before sanitizeForPreview, which strips block-level code spans it relies
        // on to leave URLs inside a code block un-coloured.
        val rendered = if (roomId == null) {
            htmlRenderer.get().render(formattedBody).colorBareLinks().sanitizeForPreview().flattenForPreview().trimForPreview()
        } else {
            val (pills, textRenderer) = pillProcessorsFor(roomId)
            textRenderer.render(htmlRenderer.get().render(formattedBody, pills)).colorBareLinks().sanitizeForPreview().flattenForPreview().trimForPreview()
        }
        previewCache.put(key, SpannableStringBuilder(rendered))
        return rendered
    }

    // Block-level spans don't render in a one-line preview: a blockquote draws its stripe/indent and a
    // code block fills a full-width background bar, so drop those (keeping inline content — pills,
    // emotes, links, inline code, bold/italic). The spoiler span is kept so it stays hidden; its blur
    // is made to render by giving the room-list TextView a software layer (see RoomSummaryItem).
    private fun CharSequence.sanitizeForPreview(): CharSequence {
        val spanned = this as? Spanned ?: return this
        val blocks = spanned.getSpans(0, spanned.length, im.vector.app.features.html.QuoteMarginSpan::class.java).toList() +
                spanned.getSpans(0, spanned.length, im.vector.app.features.html.HtmlCodeSpan::class.java).filter { it.isBlock } +
                // Paragraph vertical padding renders as a blank line above/below in the one-line preview.
                spanned.getSpans(0, spanned.length, me.gujun.android.span.style.VerticalPaddingSpan::class.java).toList() +
                // A heading scales the text and reserves its own line height, growing the whole row.
                spanned.getSpans(0, spanned.length, io.noties.markwon.core.spans.HeadingSpan::class.java).toList()
        if (blocks.isEmpty()) return this
        val builder = this as? SpannableStringBuilder ?: SpannableStringBuilder(this)
        blocks.forEach { builder.removeSpan(it) }
        return builder
    }

    // Plain-text preview: still run the text renderer so a bare permalink / @room pills, then colour links.
    private fun renderPlainPreview(roomId: String?, plainBody: CharSequence): CharSequence {
        val resolved = if (roomId == null) plainBody else pillProcessorsFor(roomId).second.render(plainBody)
        return resolved.colorBareLinks().flattenForPreview().trimForPreview()
    }

    // The room-list / thread preview is a single line, so a block element (blockquote, code, list) must
    // not break onto its own line below "Name:". Collapse any run of whitespace containing a newline to a
    // single space, dropping it entirely at the edges, while preserving emote/pill spans.
    private fun CharSequence.flattenForPreview(): CharSequence {
        if (indexOf('\n') < 0 && indexOf('\r') < 0) return this
        val builder = this as? SpannableStringBuilder ?: SpannableStringBuilder(this)
        fun Char.isFlattenable() = this == '\n' || this == '\r' || this == ' ' || this == '\t'
        var i = 0
        while (i < builder.length) {
            if (builder[i] == '\n' || builder[i] == '\r') {
                var j = i + 1
                while (j < builder.length && builder[j].isFlattenable()) j++
                val replacement = if (i == 0 || j >= builder.length) "" else " "
                builder.replace(i, j, replacement)
                i += replacement.length
            } else {
                i++
            }
        }
        return builder
    }

    // Strip outer whitespace so the preview doesn't render with leading/trailing padding — the timeline
    // trims the same way (MessageItemFactory.trimUncoveredWhitespace); flattenForPreview only drops
    // newline runs, leaving bare leading/trailing spaces/tabs (and it early-returns when there's no
    // newline at all). subSequence keeps the emote/pill spans.
    private fun CharSequence.trimForPreview(): CharSequence {
        fun Char.isTrimable() = this == '\n' || this == '\r' || this == ' ' || this == '\t'
        var start = 0
        while (start < length && this[start].isTrimable()) start++
        var end = length
        while (end > start && this[end - 1].isTrimable()) end--
        return if (start == 0 && end == length) this else subSequence(start, end)
    }

    // Markwon already colours <a> links and the pill pipeline pills mentions/permalinks; this only adds a
    // link colour to bare http(s) URLs in plain text. Previews aren't independently tappable, so colour only.
    private fun CharSequence.colorBareLinks(): CharSequence {
        if (!contains("://")) return this
        val builder = this as? SpannableStringBuilder ?: SpannableStringBuilder(this)
        val linkColor = colorProvider.getColorFromAttribute(android.R.attr.textColorLink)
        webUrlRegex.findAll(builder.toString()).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            val covered = builder.getSpans(start, end, URLSpan::class.java).isNotEmpty() ||
                    builder.getSpans(start, end, PillImageSpan::class.java).isNotEmpty() ||
                    // A URL inside inline code or a code block stays verbatim, not link-coloured.
                    builder.getSpans(start, end, im.vector.app.features.html.HtmlCodeSpan::class.java).isNotEmpty()
            if (!covered) builder.setSpan(ForegroundColorSpan(linkColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return builder
    }

    // An emote keeps its "* Sender " prefix even where the author is otherwise omitted — its body reads
    // as an action attributed to the sender.
    private fun simpleFormat(senderName: String, body: CharSequence, appendAuthor: Boolean, isEmote: Boolean = false): CharSequence {
        val emojiBody = body.prepareForDisplay()
        if (!appendAuthor && !isEmote) return emojiBody
        // SpannableStringBuilder (not the gujun span DSL) so [body]'s emote ReplacementSpans are preserved.
        return android.text.SpannableStringBuilder().apply {
            if (isEmote) append("* ")
            val start = length
            // Isolate the sender name so an RTL name doesn't flip the whole "Name: message" line to RTL.
            // Neutralize BEFORE wrapping and emoji-spanify only afterwards: unicodeWrap's own embedding
            // chars (U+202A..U+202C) fall in the neutralized range and must survive.
            val wrappedName = androidx.core.text.BidiFormatter.getInstance().unicodeWrap(senderName.neutralizeDirectionOverrides())
            append(messageEmojiSpanify?.spanify(wrappedName) ?: wrappedName)
            setSpan(
                    android.text.style.ForegroundColorSpan(colorProvider.getColorFromAttribute(im.vector.lib.ui.styles.R.attr.vctr_content_primary)),
                    start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            append(if (isEmote) " " else ": ")
            append(emojiBody)
        }
    }
}
