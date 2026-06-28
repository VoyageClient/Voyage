/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.format

import dagger.Lazy
import im.vector.app.features.home.room.detail.timeline.tools.withEmojis
import im.vector.app.R
import im.vector.app.core.extensions.getVectorLastMessageContent
import im.vector.app.features.pgp.PgpDecryptor
import im.vector.app.core.extensions.orEmpty
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.DrawableProvider
import im.vector.app.core.resources.StringProvider
import android.text.Spanned
import im.vector.app.features.html.EmoteImageSpan
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.media.isMediaHiddenInRoom
import im.vector.lib.strings.CommonStrings
import me.gujun.android.span.image
import me.gujun.android.span.span
import org.commonmark.node.Document
import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessagePollContent
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.asMessageAudioEvent
import org.matrix.android.sdk.api.session.room.model.relation.ReactionContent
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.util.ContentUtils
import javax.inject.Inject

class DisplayableEventFormatter @Inject constructor(
        private val stringProvider: StringProvider,
        private val colorProvider: ColorProvider,
        private val drawableProvider: DrawableProvider,
        private val noticeEventFormatter: NoticeEventFormatter,
        private val htmlRenderer: Lazy<EventHtmlRenderer>,
        private val pgpDecryptor: PgpDecryptor,
        private val imagePackProvider: Lazy<im.vector.app.features.imagepack.ImagePackProvider>,
        private val activeSessionHolder: Lazy<im.vector.app.core.di.ActiveSessionHolder>,
        private val vectorPreferences: im.vector.app.features.settings.VectorPreferences,
) {

    /**
     * Build the "Reacted with …" text for a reaction key. Unicode keys render as the emoji; a custom
     * emote (`mxc://`) key renders as the inline image (resolved by mxc whether or not it's in a known
     * pack), falling back to ❓ when the image can't be resolved or — matching the timeline — when media is
     * hidden for the room and the reaction wasn't sent by us (so it's never fetched).
     */
    private fun formatReaction(roomId: String?, key: String, reactionSenderId: String?): CharSequence {
        if (key.isMxcUrl()) {
            val session = activeSessionHolder.get().getSafeActiveSession()
            val addedByMe = reactionSenderId != null && reactionSenderId == session?.myUserId
            val blockMedia = session == null || (!addedByMe && isMediaHiddenInRoom(roomId, session, vectorPreferences))
            val rendered = if (!blockMedia) {
                val shortcode = roomId?.let { id -> imagePackProvider.get().getEmoticons(id).firstOrNull { it.mxcUrl == key } }?.shortcode.orEmpty()
                val html = "<img data-mx-emoticon src=\"$key\" alt=\":$shortcode:\" title=\"$shortcode\" height=\"32\"/>"
                htmlRenderer.get().render(html).takeIf { (it as? Spanned)?.getSpans(0, it.length, EmoteImageSpan::class.java)?.isNotEmpty() == true }
            } else {
                null
            }
            return reactionTemplate(rendered ?: QUESTION_MARK_EMOJI.withEmojis())
        }
        return stringProvider.getString(CommonStrings.sent_a_reaction, key).withEmojis()
    }

    // Insert [display] (which may carry emote image spans) into the "Reacted with: %s" template.
    private fun reactionTemplate(display: CharSequence): CharSequence {
        val marker = "\u0001"
        val template = stringProvider.getString(CommonStrings.sent_a_reaction, marker)
        val idx = template.indexOf(marker)
        if (idx < 0) return display
        return android.text.SpannableStringBuilder()
                .append(template.subSequence(0, idx))
                .append(display)
                .append(template.subSequence(idx + marker.length, template.length))
    }

    fun format(timelineEvent: TimelineEvent, isDm: Boolean, appendAuthor: Boolean, unhandledFallback: Boolean = false): CharSequence {
        if (timelineEvent.root.isRedacted()) {
            return noticeEventFormatter.formatRedactedEvent(timelineEvent.root)
        }

        if (timelineEvent.root.isEncrypted() &&
                timelineEvent.root.mxDecryptionResult == null) {
            return stringProvider.getString(CommonStrings.encrypted_message)
        }

        val senderName = timelineEvent.senderInfo.disambiguatedDisplayName

        return when (timelineEvent.root.getClearType()) {
            EventType.MESSAGE -> {
                timelineEvent.getVectorLastMessageContent()?.let { messageContent ->
                    val pgp = (messageContent as? MessageTextContent)?.let { pgpDecryptor.peekDecryptedBody(it.body) }
                    if (pgp != null) {
                        return@let simpleFormat(senderName, pgp, appendAuthor)
                    }
                    when (messageContent.msgType) {
                        MessageType.MSGTYPE_TEXT -> {
                            val preview = messageContent.previewText()
                            if (preview.formattedBody != null) {
                                // Render the formatted HTML so custom emotes survive as image spans.
                                simpleFormat(senderName, htmlRenderer.get().render(preview.formattedBody).stripPreviewLinkStyling(), appendAuthor)
                            } else {
                                simpleFormat(senderName, preview.body, appendAuthor)
                            }
                        }
                        MessageType.MSGTYPE_VERIFICATION_REQUEST -> {
                            simpleFormat(senderName, stringProvider.getString(CommonStrings.verification_request), appendAuthor)
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
                        MessageType.MSGTYPE_LOCATION -> {
                            noticeEventFormatter.formatLocationNotice(timelineEvent.root, senderName)
                        }
                        else -> {
                            simpleFormat(senderName, messageContent.body, appendAuthor)
                        }
                    }
                } ?: span { }
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
                simpleFormat(senderName, stringProvider.getString(CommonStrings.sent_live_location), appendAuthor)
            }
            in EventType.ELEMENT_CALL_NOTIFY.values -> {
                simpleFormat(senderName, stringProvider.getString(CommonStrings.call_unsupported), appendAuthor)
            }
            else -> {
                val formatted = noticeEventFormatter.format(timelineEvent, isDm)
                when {
                    formatted != null -> span { text = formatted }
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
            return run {
                val localFormattedBody = htmlRenderer.get().parse(latestEdition) as Document
                val renderedBody = htmlRenderer.get().render(localFormattedBody)?.stripPreviewLinkStyling() ?: latestEdition
                renderedBody
            }
        }

        // The event have been redacted
        if (event.isRedacted()) {
            return noticeEventFormatter.formatRedactedEvent(event)
        }

        // The event is encrypted
        if (event.isEncrypted() &&
                event.mxDecryptionResult == null) {
            return stringProvider.getString(CommonStrings.encrypted_message)
        }

        return when (event.getClearType()) {
            EventType.MESSAGE -> {
                (event.getClearContent().toModel() as? MessageContent)?.let { messageContent ->
                    when (messageContent.msgType) {
                        MessageType.MSGTYPE_TEXT -> {
                            val preview = messageContent.previewText()
                            if (preview.formattedBody != null) {
                                htmlRenderer.get().render(preview.formattedBody).stripPreviewLinkStyling()
                            } else {
                                preview.body
                            }
                        }
                        MessageType.MSGTYPE_VERIFICATION_REQUEST -> {
                            stringProvider.getString(CommonStrings.verification_request)
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
                        MessageType.MSGTYPE_LOCATION -> {
                            noticeEventFormatter.formatLocationNotice(event, senderName = null)
                        }
                        else -> {
                            messageContent.body
                        }
                    }
                } ?: span { }
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
                stringProvider.getString(CommonStrings.sent_live_location)
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
        val textContent = this as? MessageTextContent
        val isReply = textContent?.relatesTo?.inReplyTo?.eventId != null
        val formattedBody = textContent?.matrixFormattedBody?.takeIf { it.isNotBlank() }
                ?.let { ContentUtils.extractUsefulTextFromHtmlReply(it) }
        val plain = textContent?.body ?: body
        val previewBody = if (isReply) ContentUtils.extractUsefulTextFromReply(plain) else plain
        return PreviewText(formattedBody, previewBody)
    }

    // Previews are plain text: drop link/underline styling so mentions and other <a> tags read as plain
    // text rather than underlined links.
    private fun CharSequence.stripPreviewLinkStyling(): CharSequence {
        val spanned = this as? Spanned ?: return this
        val clickable = spanned.getSpans(0, spanned.length, android.text.style.ClickableSpan::class.java)
        val underline = spanned.getSpans(0, spanned.length, android.text.style.UnderlineSpan::class.java)
        if (clickable.isEmpty() && underline.isEmpty()) return this
        return android.text.SpannableStringBuilder(spanned).apply {
            clickable.forEach { removeSpan(it) }
            underline.forEach { removeSpan(it) }
        }
    }

    private fun simpleFormat(senderName: String, body: CharSequence, appendAuthor: Boolean): CharSequence {
        val emojiBody = body.withEmojis()
        if (!appendAuthor) return emojiBody
        // SpannableStringBuilder (not the gujun span DSL) so [body]'s emote ReplacementSpans are preserved.
        return android.text.SpannableStringBuilder().apply {
            val start = length
            append(senderName)
            setSpan(
                    android.text.style.ForegroundColorSpan(colorProvider.getColorFromAttribute(im.vector.lib.ui.styles.R.attr.vctr_content_primary)),
                    start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            append(": ")
            append(emojiBody)
        }
    }

    companion object {
        private const val QUESTION_MARK_EMOJI = "❓"
    }
}
