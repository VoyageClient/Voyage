/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.room.send

import org.matrix.android.sdk.api.extensions.ensureNotEmpty
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.AudioInfo
import org.matrix.android.sdk.api.session.room.model.message.AudioWaveformInfo
import org.matrix.android.sdk.api.session.room.model.message.FileInfo
import org.matrix.android.sdk.api.session.room.model.message.ImageInfo
import org.matrix.android.sdk.api.session.room.model.message.LocationAsset
import org.matrix.android.sdk.api.session.room.model.message.LocationAssetType
import org.matrix.android.sdk.api.session.room.model.message.LocationInfo
import org.matrix.android.sdk.api.session.room.model.message.Mentions
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageBeaconLocationDataContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContentWithFormattedBody
import org.matrix.android.sdk.api.session.room.model.message.MessageEndPollContent
import org.matrix.android.sdk.api.session.room.model.message.MessageFileContent
import org.matrix.android.sdk.api.session.room.model.message.MessageFormat
import org.matrix.android.sdk.api.session.room.model.message.MessageImageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageLocationContent
import org.matrix.android.sdk.api.session.room.model.message.MessagePollContent
import org.matrix.android.sdk.api.session.room.model.message.MessagePollResponseContent
import org.matrix.android.sdk.api.session.room.model.message.MessageStickerContent
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.model.message.PollAnswer
import org.matrix.android.sdk.api.session.room.model.message.PollCreationInfo
import org.matrix.android.sdk.api.session.room.model.message.PollQuestion
import org.matrix.android.sdk.api.session.room.model.message.PollResponse
import org.matrix.android.sdk.api.session.room.model.message.PollType
import org.matrix.android.sdk.api.session.room.model.message.ThumbnailInfo
import org.matrix.android.sdk.api.session.room.model.message.VideoInfo
import org.matrix.android.sdk.api.session.room.model.message.getFileName
import org.matrix.android.sdk.api.session.room.model.relation.ReactionContent
import org.matrix.android.sdk.api.session.room.model.relation.ReactionInfo
import org.matrix.android.sdk.api.session.room.model.relation.RelationDefaultContent
import org.matrix.android.sdk.api.session.room.model.relation.ReplyToContent
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getLastEditNewContent
import org.matrix.android.sdk.api.session.room.timeline.getLastMessageContent
import org.matrix.android.sdk.api.session.room.timeline.isReply
import org.matrix.android.sdk.api.util.TextContent
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.content.ThumbnailExtractor
import org.matrix.android.sdk.internal.session.permalinks.PermalinkFactory
import org.matrix.android.sdk.internal.session.room.send.model.EventRedactBody
import org.matrix.android.sdk.internal.session.room.send.pills.TextPillsUtils
import org.matrix.android.sdk.internal.util.time.Clock
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Creates local echo of events for room events.
 * A local echo is an event that is persisted even if not yet sent to the server,
 * in an optimistic way (as if the server as responded immediately). Local echo are using a local id,
 * (the transaction ID), this id is used when receiving an event from a sync to check if this event
 * is matching an existing local echo.
 *
 * The transactionId is used as loc
 */
internal class LocalEchoEventFactory @Inject constructor(
        private val videoMetadataExtractor: VideoMetadataExtractor,
        @UserId private val userId: String,
        private val markdownParser: MarkdownParser,
        private val textPillsUtils: TextPillsUtils,
        private val thumbnailExtractor: ThumbnailExtractor,
        private val waveformSanitizer: WaveFormSanitizer,
        private val localEchoRepository: LocalEchoRepository,
        private val permalinkFactory: PermalinkFactory,
        private val clock: Clock,
) {
    fun createTextEvent(roomId: String, msgType: String, text: CharSequence, autoMarkdown: Boolean, additionalContent: Content? = null): Event {
        if (msgType == MessageType.MSGTYPE_TEXT || msgType == MessageType.MSGTYPE_EMOTE) {
            return createFormattedTextEvent(roomId, createTextContent(text, autoMarkdown), msgType, additionalContent)
        }
        val content = MessageTextContent(msgType = msgType, body = text.toString())
        return createMessageEvent(roomId, content, additionalContent)
    }

    private fun createTextContent(text: CharSequence, autoMarkdown: Boolean): TextContent {
        if (autoMarkdown) {
            return markdownParser.parse(text)
        } else {
            // Try to detect pills
            textPillsUtils.processSpecialSpansToHtml(text)?.let {
                return TextContent(text.toString(), it)
            }
        }

        return TextContent(text.toString())
    }

    /**
     * The formatted (HTML) body the SDK would derive for [text] — the same markdown + mention/pill
     * rendering used on a normal send — or null when it would be plain. Lets callers that must
     * produce the formatted body *before* the SDK sees it (e.g. PGP, which encrypts the body) reuse
     * the genuine path instead of a divergent parser.
     */
    fun computeFormattedHtml(text: CharSequence, autoMarkdown: Boolean): String? =
            createTextContent(text, autoMarkdown).formattedText

    fun createFormattedTextEvent(roomId: String, textContent: TextContent, msgType: String, additionalContent: Content? = null): Event {
        return createMessageEvent(roomId, textContent.toMessageTextContent(msgType, selfUserId = userId), additionalContent)
    }

    fun createReplaceTextEvent(
            roomId: String,
            targetEventId: String,
            newBodyText: CharSequence,
            newBodyFormattedText: CharSequence?,
            newBodyAutoMarkdown: Boolean,
            msgType: String,
            compatibilityText: String,
            additionalContent: Content? = null,
    ): Event {
        val content = if (newBodyFormattedText != null) {
            TextContent(newBodyText.toString(), newBodyFormattedText.toString()).toMessageTextContent(msgType, selfUserId = userId)
        } else {
            createTextContent(newBodyText, newBodyAutoMarkdown).toMessageTextContent(msgType, selfUserId = userId)
        }.toContent()
        return createMessageEvent(
                roomId,
                MessageTextContent(
                        msgType = msgType,
                        body = compatibilityText,
                        relatesTo = RelationDefaultContent(RelationType.REPLACE, targetEventId),
                        newContent = content,
                ),
                additionalContent,
        )
    }

    /**
     * Build an m.replace edit for a media event (image/video/file/audio/sticker) that updates,
     * adds or removes its MSC2530 caption while preserving all the media fields (url, info, …).
     * An empty [newCaption] removes the caption, restoring the captionless form a fresh upload
     * uses: `body` holds the file name and the `filename` field is omitted.
     */
    fun createMediaCaptionReplaceEvent(
            roomId: String,
            targetEvent: TimelineEvent,
            newCaption: CharSequence,
            newFormattedCaption: String?,
    ): Event? {
        val targetEventId = targetEvent.eventId
        val mediaContent = targetEvent.getLastMessageContent() as? MessageWithAttachmentContent ?: return null
        val fileName = mediaContent.getFileName()
        val baseContent = (targetEvent.getLastEditNewContent() ?: targetEvent.root.getClearContent().orEmpty())
                .toMutableMap()
                .apply {
                    remove("m.relates_to")
                    remove("m.new_content")
                }
        val caption = newCaption.toString()
        val newInnerContent: Content = baseContent.toMutableMap().apply {
            if (caption.isNotEmpty()) {
                // With a caption, `filename` carries the file name so `body` is the caption (MSC2530).
                put("filename", fileName)
                put("body", caption)
                if (newFormattedCaption != null) {
                    put("format", MessageFormat.FORMAT_MATRIX_HTML)
                    put("formatted_body", newFormattedCaption)
                } else {
                    remove("format")
                    remove("formatted_body")
                }
            } else {
                // Caption removed: restore the captionless form — `body` is the file name and the
                // `filename` field is omitted, exactly like an upload sent without a caption.
                put("body", fileName)
                remove("filename")
                remove("format")
                remove("formatted_body")
            }
        }
        val outerContent: Content = newInnerContent.toMutableMap().apply {
            put("m.relates_to", RelationDefaultContent(RelationType.REPLACE, targetEventId).toContent())
            put("m.new_content", newInnerContent)
        }
        // Decrypted/parsed content goes through Moshi's Any adapter, which reads every JSON number
        // as Double. Re-serializing media info would emit "w":1080.0, which Synapse rejects
        // (M_BAD_JSON "Bad JSON value: float"). Round-trip whole-number Doubles back to Long.
        @Suppress("UNCHECKED_CAST")
        return createEvent(roomId, targetEvent.root.getClearType(), coerceWholeDoublesToLongs(outerContent) as Content)
    }

    private fun coerceWholeDoublesToLongs(value: Any?): Any? = when (value) {
        is Double -> if (value.isFinite() && value % 1.0 == 0.0 &&
                value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()) {
            value.toLong()
        } else {
            value
        }
        is Map<*, *> -> value.mapValues { coerceWholeDoublesToLongs(it.value) }
        is List<*> -> value.map { coerceWholeDoublesToLongs(it) }
        else -> value
    }

    private fun createPollContent(
            question: String,
            options: List<String>,
            pollType: PollType,
    ): MessagePollContent {
        return MessagePollContent(
                unstablePollCreationInfo = PollCreationInfo(
                        question = PollQuestion(unstableQuestion = question),
                        kind = pollType,
                        answers = options.map { option ->
                            PollAnswer(id = UUID.randomUUID().toString(), unstableAnswer = option)
                        }
                )
        )
    }

    fun createPollReplaceEvent(
            roomId: String,
            pollType: PollType,
            targetEventId: String,
            question: String,
            options: List<String>,
            additionalContent: Content? = null,
    ): Event {
        val newContent = MessagePollContent(
                relatesTo = RelationDefaultContent(RelationType.REPLACE, targetEventId),
                newContent = createPollContent(question, options, pollType).toContent()
        )
        val localId = LocalEcho.createLocalEchoId()
        return Event(
                roomId = roomId,
                originServerTs = dummyOriginServerTs(),
                senderId = userId,
                eventId = localId,
                type = EventType.POLL_START.unstable,
                content = newContent.toContent().plus(additionalContent.orEmpty())
        )
    }

    fun createPollReplyEvent(
            roomId: String,
            pollEventId: String,
            answerId: String,
            additionalContent: Content? = null,
    ): Event {
        val content = MessagePollResponseContent(
                body = answerId,
                relatesTo = RelationDefaultContent(
                        type = RelationType.REFERENCE,
                        eventId = pollEventId
                ),
                unstableResponse = PollResponse(answers = listOf(answerId))
        )
        val localId = LocalEcho.createLocalEchoId()
        return Event(
                roomId = roomId,
                originServerTs = dummyOriginServerTs(),
                senderId = userId,
                eventId = localId,
                type = EventType.POLL_RESPONSE.unstable,
                content = content.toContent().plus(additionalContent.orEmpty()),
                unsignedData = UnsignedData(age = null, transactionId = localId)
        )
    }

    fun createPollEvent(
            roomId: String,
            pollType: PollType,
            question: String,
            options: List<String>,
            additionalContent: Content? = null,
    ): Event {
        val content = createPollContent(question, options, pollType)
        val localId = LocalEcho.createLocalEchoId()
        return Event(
                roomId = roomId,
                originServerTs = dummyOriginServerTs(),
                senderId = userId,
                eventId = localId,
                type = EventType.POLL_START.unstable,
                content = content.toContent().plus(additionalContent.orEmpty()),
                unsignedData = UnsignedData(age = null, transactionId = localId)
        )
    }

    fun createEndPollEvent(
            roomId: String,
            eventId: String,
            additionalContent: Content? = null,
    ): Event {
        val content = MessageEndPollContent(
                relatesTo = RelationDefaultContent(
                        type = RelationType.REFERENCE,
                        eventId = eventId
                ),
                unstableText = "Ended poll",
        )
        val localId = LocalEcho.createLocalEchoId()
        return Event(
                roomId = roomId,
                originServerTs = dummyOriginServerTs(),
                senderId = userId,
                eventId = localId,
                type = EventType.POLL_END.unstable,
                content = content.toContent().plus(additionalContent.orEmpty()),
                unsignedData = UnsignedData(age = null, transactionId = localId)
        )
    }

    fun createStaticLocationEvent(
            roomId: String,
            latitude: Double,
            longitude: Double,
            uncertainty: Double?,
            isUserLocation: Boolean,
            additionalContent: Content? = null,
    ): Event {
        val geoUri = buildGeoUri(latitude, longitude, uncertainty)
        val assetType = if (isUserLocation) LocationAssetType.SELF else LocationAssetType.PIN
        val content = MessageLocationContent(
                geoUri = geoUri,
                body = geoUri,
                unstableLocationInfo = LocationInfo(geoUri = geoUri, description = geoUri),
                unstableLocationAsset = LocationAsset(type = assetType),
                unstableTimestampMillis = clock.epochMillis(),
                unstableText = geoUri
        )
        return createMessageEvent(roomId, content, additionalContent)
    }

    fun createLiveLocationEvent(
            beaconInfoEventId: String,
            roomId: String,
            latitude: Double,
            longitude: Double,
            uncertainty: Double?,
            additionalContent: Content? = null,
    ): Event {
        val geoUri = buildGeoUri(latitude, longitude, uncertainty)
        val content = MessageBeaconLocationDataContent(
                body = geoUri,
                relatesTo = RelationDefaultContent(
                        type = RelationType.REFERENCE,
                        eventId = beaconInfoEventId
                ),
                unstableLocationInfo = LocationInfo(geoUri = geoUri, description = geoUri),
                unstableTimestampMillis = clock.epochMillis(),
        )
        val localId = LocalEcho.createLocalEchoId()
        return Event(
                roomId = roomId,
                originServerTs = dummyOriginServerTs(),
                senderId = userId,
                eventId = localId,
                type = EventType.BEACON_LOCATION_DATA.unstable,
                content = content.toContent().plus(additionalContent.orEmpty()),
                unsignedData = UnsignedData(age = null, transactionId = localId)
        )
    }

    fun createReplaceTextOfReply(
            roomId: String,
            eventReplaced: TimelineEvent,
            @Suppress("UNUSED_PARAMETER")
            originalEvent: TimelineEvent,
            replyText: CharSequence,
            replyTextFormatted: String?,
            autoMarkdown: Boolean,
            msgType: String,
            compatibilityText: String,
            additionalContent: Content? = null,
            targetEventId: String? = null,
    ): Event {
        // Modern reply edit: the new_content carries only the user-typed reply text. No
        // legacy "> <@user:server>" quote prefix in body, no <mx-reply> block in
        // formatted_body. The outer m.relates_to (m.replace) still targets the edited event;
        // the original event keeps its own m.in_reply_to relation so clients render the
        // reply header from cached state rather than from a duplicated embedded snapshot.
        val plainBody = replyText.toString()
        // See createReplyTextContent: explicit formatted body wins; otherwise only auto-format when
        // markdown is on and actually produced formatting (never a PGP armored body).
        val htmlBody = replyTextFormatted
                ?: if (autoMarkdown && !isPgpArmoredBody(plainBody)) markdownParser.parse(replyText).formattedText else null
        val isFormatted = htmlBody != null

        return createMessageEvent(
                roomId,
                MessageTextContent(
                        msgType = msgType,
                        body = compatibilityText,
                        relatesTo = RelationDefaultContent(RelationType.REPLACE, targetEventId ?: eventReplaced.root.eventId),
                        newContent = MessageTextContent(
                                msgType = msgType,
                                format = if (isFormatted) MessageFormat.FORMAT_MATRIX_HTML else null,
                                body = plainBody,
                                formattedBody = if (isFormatted) htmlBody else null,
                        )
                                .toContent()
                ),
                additionalContent,
        )
    }

    fun createMediaEvent(
            roomId: String,
            attachment: ContentAttachmentData,
            rootThreadEventId: String?,
            relatesTo: RelationDefaultContent?,
            additionalContent: Content? = null,
            captionText: CharSequence? = null,
            captionFormattedText: String? = null,
            autoMarkdown: Boolean = false,
            mentions: Mentions? = null,
    ): Event {
        return when (attachment.type) {
            ContentAttachmentData.Type.IMAGE -> createImageEvent(
                    roomId, attachment, rootThreadEventId, relatesTo, additionalContent, captionText, captionFormattedText, autoMarkdown, mentions)
            ContentAttachmentData.Type.VIDEO -> createVideoEvent(
                    roomId, attachment, rootThreadEventId, relatesTo, additionalContent, captionText, captionFormattedText, autoMarkdown, mentions)
            ContentAttachmentData.Type.AUDIO -> createAudioEvent(
                    roomId,
                    attachment,
                    isVoiceMessage = false,
                    rootThreadEventId = rootThreadEventId,
                    relatesTo,
                    additionalContent,
                    captionText, captionFormattedText, autoMarkdown, mentions,
            )
            ContentAttachmentData.Type.VOICE_MESSAGE -> createAudioEvent(
                    roomId,
                    attachment,
                    isVoiceMessage = true,
                    rootThreadEventId = rootThreadEventId,
                    relatesTo,
                    additionalContent,
                    captionText, captionFormattedText, autoMarkdown, mentions,
            )
            ContentAttachmentData.Type.FILE -> createFileEvent(
                    roomId, attachment, rootThreadEventId, relatesTo, additionalContent, captionText, captionFormattedText, autoMarkdown, mentions)
        }
    }

    private data class MediaBodyParts(val body: String, val filename: String?, val format: String?, val formattedBody: String?)

    // A PGP-over-plaintext body (its body/caption is an ASCII-armored block). We must not run such a
    // body through the markdown formatter — the armored text contains '-' etc. and would be mangled.
    private fun isPgpArmoredBody(body: String): Boolean =
            body.contains("-----BEGIN PGP MESSAGE-----") && body.contains("-----END PGP MESSAGE-----")

    private fun buildMediaBody(attachment: ContentAttachmentData, fallback: String, captionText: CharSequence?, captionFormattedText: String?, autoMarkdown: Boolean): MediaBodyParts {
        val name = attachment.name ?: fallback
        val plain = captionText?.toString()
        if (plain.isNullOrEmpty()) {
            return MediaBodyParts(body = name, filename = null, format = null, formattedBody = null)
        }
        // A PGP armored caption is never auto-formatted (markdown would mangle the ciphertext); its
        // formatted_body, if any, is supplied explicitly via captionFormattedText.
        val html = when {
            captionFormattedText != null -> captionFormattedText
            isPgpArmoredBody(plain) -> null
            // Parse the CharSequence (not the stringified plain) so emote/mention spans in the caption
            // are serialized into a formatted body.
            else -> markdownParser.parse(captionText, force = true, advanced = autoMarkdown).takeFormatted()
        }
        val isFormatted = html != null && html != plain
        return MediaBodyParts(
                body = plain,
                filename = name,
                format = if (isFormatted) MessageFormat.FORMAT_MATRIX_HTML else null,
                formattedBody = if (isFormatted) html else null,
        )
    }

    fun createReactionEvent(roomId: String, targetEventId: String, reaction: String, additionalContent: Content? = null): Event {
        val content = ReactionContent(
                ReactionInfo(
                        RelationType.ANNOTATION,
                        targetEventId,
                        reaction
                )
        )
        val localId = LocalEcho.createLocalEchoId()
        return Event(
                roomId = roomId,
                originServerTs = dummyOriginServerTs(),
                senderId = userId,
                eventId = localId,
                type = EventType.REACTION,
                content = content.toContent().plus(additionalContent.orEmpty()),
                unsignedData = UnsignedData(age = null, transactionId = localId)
        )
    }

    private fun createImageEvent(
            roomId: String,
            attachment: ContentAttachmentData,
            rootThreadEventId: String?,
            relatesTo: RelationDefaultContent?,
            additionalContent: Content?,
            captionText: CharSequence? = null,
            captionFormattedText: String? = null,
            autoMarkdown: Boolean = false,
            mentions: Mentions? = null,
    ): Event {
        var width = attachment.outputWidth
        var height = attachment.outputHeight

        // A size the sender chose is already in display orientation, so it must not be swapped again.
        if (attachment.compressionWidth == null &&
                attachment.exifOrientation in ContentAttachmentData.EXIF_ORIENTATIONS_SWAPPING_SIZE) {
            val tmp = width
            width = height
            height = tmp
        }

        val body = buildMediaBody(attachment, "image", captionText, captionFormattedText, autoMarkdown)
        val content = MessageImageContent(
                msgType = MessageType.MSGTYPE_IMAGE,
                body = body.body,
                filename = body.filename,
                format = body.format,
                formattedBody = body.formattedBody,
                info = ImageInfo(
                        mimeType = attachment.getSafeMimeType(),
                        width = width?.toInt() ?: 0,
                        height = height?.toInt() ?: 0,
                        size = attachment.size
                ),
                url = attachment.queryUri,
                relatesTo = relatesTo ?: rootThreadEventId?.let { generateThreadRelationContent(it) },
                mentions = mentions,
        )
        return createMessageEvent(roomId, content, additionalContent)
    }

    private fun createVideoEvent(
            roomId: String,
            attachment: ContentAttachmentData,
            rootThreadEventId: String?,
            relatesTo: RelationDefaultContent?,
            additionalContent: Content?,
            captionText: CharSequence? = null,
            captionFormattedText: String? = null,
            autoMarkdown: Boolean = false,
            mentions: Mentions? = null,
    ): Event {
        val (width, height) = videoMetadataExtractor.getVideoSize(attachment)

        var thumbnailInfo = thumbnailExtractor.extractThumbnail(attachment, withBlurHash = false)?.let {
            ThumbnailInfo(
                    width = it.width,
                    height = it.height,
                    size = it.size,
                    mimeType = it.mimeType
            )
        }
        // The decoded frame is ground truth for display size: the metadata keys report coded
        // dimensions, which lie for anamorphic files (SAR != 1:1, e.g. coded 2880x2160 displaying
        // as 1620x2160) and files with bogus headers. When the frame's aspect disagrees with the
        // metadata's, send the frame's dimensions.
        val frameW = thumbnailInfo?.width ?: 0
        val frameH = thumbnailInfo?.height ?: 0
        val aspectDisagrees = frameW > 0 && frameH > 0 && width > 0 && height > 0 &&
                kotlin.math.abs(width.toFloat() / height - frameW.toFloat() / frameH) > 0.01f * (frameW.toFloat() / frameH)
        var (finalWidth, finalHeight) = if (aspectDisagrees) frameW to frameH else width to height
        // A size the sender chose wins over anything measured from the source, and the thumbnail is
        // declared at the same shape so the two never disagree — a deliberate stretch is applied to
        // the still as well, which is what the video will look like.
        val targetWidth = attachment.compressionWidth
        val targetHeight = attachment.compressionHeight
        if (targetWidth != null && targetHeight != null && targetWidth > 0 && targetHeight > 0) {
            finalWidth = targetWidth
            finalHeight = targetHeight
            thumbnailInfo = thumbnailInfo?.let {
                val thumbnailHeight = it.height.coerceAtLeast(1)
                val thumbnailWidth = (thumbnailHeight.toFloat() * targetWidth / targetHeight).roundToInt()
                it.copy(width = thumbnailWidth.coerceAtLeast(1), height = thumbnailHeight)
            }
        }
        val body = buildMediaBody(attachment, "video", captionText, captionFormattedText, autoMarkdown)
        val content = MessageVideoContent(
                msgType = MessageType.MSGTYPE_VIDEO,
                body = body.body,
                filename = body.filename,
                format = body.format,
                formattedBody = body.formattedBody,
                videoInfo = VideoInfo(
                        mimeType = attachment.getSafeMimeType(),
                        width = finalWidth,
                        height = finalHeight,
                        size = attachment.size,
                        duration = attachment.duration?.toInt() ?: 0,
                        // Glide will be able to use the local path and extract a thumbnail.
                        thumbnailUrl = attachment.queryUri,
                        thumbnailInfo = thumbnailInfo
                ),
                url = attachment.queryUri,
                relatesTo = relatesTo ?: rootThreadEventId?.let { generateThreadRelationContent(it) },
                mentions = mentions,
        )
        return createMessageEvent(roomId, content, additionalContent)
    }

    private fun createAudioEvent(
            roomId: String,
            attachment: ContentAttachmentData,
            isVoiceMessage: Boolean,
            rootThreadEventId: String?,
            relatesTo: RelationDefaultContent?,
            additionalContent: Content?,
            captionText: CharSequence? = null,
            captionFormattedText: String? = null,
            autoMarkdown: Boolean = false,
            mentions: Mentions? = null,
    ): Event {
        val body = buildMediaBody(attachment, "audio", captionText, captionFormattedText, autoMarkdown)
        val content = MessageAudioContent(
                msgType = MessageType.MSGTYPE_AUDIO,
                body = body.body,
                filename = body.filename,
                format = body.format,
                formattedBody = body.formattedBody,
                audioInfo = AudioInfo(
                        duration = attachment.duration?.toInt(),
                        mimeType = attachment.getSafeMimeType()?.takeIf { it.isNotBlank() },
                        size = attachment.size
                ),
                url = attachment.queryUri,
                audioWaveformInfo = if (!isVoiceMessage) null else AudioWaveformInfo(
                        duration = attachment.duration?.toInt(),
                        waveform = waveformSanitizer.sanitize(attachment.waveform)
                ),
                voiceMessageIndicator = if (!isVoiceMessage) null else emptyMap(),
                relatesTo = relatesTo ?: rootThreadEventId?.let { generateThreadRelationContent(it) },
                mentions = mentions,
        )
        return createMessageEvent(roomId, content, additionalContent)
    }

    private fun createFileEvent(
            roomId: String,
            attachment: ContentAttachmentData,
            rootThreadEventId: String?,
            relatesTo: RelationDefaultContent?,
            additionalContent: Content?,
            captionText: CharSequence? = null,
            captionFormattedText: String? = null,
            autoMarkdown: Boolean = false,
            mentions: Mentions? = null,
    ): Event {
        val body = buildMediaBody(attachment, "file", captionText, captionFormattedText, autoMarkdown)
        val content = MessageFileContent(
                msgType = MessageType.MSGTYPE_FILE,
                body = body.body,
                filename = body.filename,
                format = body.format,
                formattedBody = body.formattedBody,
                info = FileInfo(
                        mimeType = attachment.getSafeMimeType()?.takeIf { it.isNotBlank() },
                        size = attachment.size
                ),
                url = attachment.queryUri,
                relatesTo = relatesTo ?: rootThreadEventId?.let { generateThreadRelationContent(it) },
                mentions = mentions,
        )
        return createMessageEvent(roomId, content, additionalContent)
    }

    private fun createMessageEvent(roomId: String, content: MessageContent, additionalContent: Content?): Event {
        return createEvent(roomId, EventType.MESSAGE, content.toContent(), additionalContent)
    }

    fun createEvent(roomId: String, type: String, content: Content?, additionalContent: Content? = null): Event {
        val newContent = enhanceStickerIfNeeded(type, content) ?: content
        val updatedNewContent = newContent?.plus(additionalContent.orEmpty()) ?: additionalContent
        val localId = LocalEcho.createLocalEchoId()
        return Event(
                roomId = roomId,
                originServerTs = dummyOriginServerTs(),
                senderId = userId,
                eventId = localId,
                type = type,
                content = updatedNewContent,
                unsignedData = UnsignedData(age = null, transactionId = localId)
        )
    }

    /**
     * Enhance sticker to support threads fallback if needed.
     */
    private fun enhanceStickerIfNeeded(type: String, content: Content?): Content? {
        var newContent: Content? = null
        if (type == EventType.STICKER) {
            val isThread = (content.toModel<MessageStickerContent>())?.relatesTo?.type == RelationType.THREAD
            val rootThreadEventId = (content.toModel<MessageStickerContent>())?.relatesTo?.eventId
            if (isThread && rootThreadEventId != null) {
                val newRelationalDefaultContent = (content.toModel<MessageStickerContent>())?.relatesTo?.copy(
                        inReplyTo = ReplyToContent(eventId = localEchoRepository.getLatestThreadEvent(rootThreadEventId))
                )
                newContent = (content.toModel<MessageStickerContent>())?.copy(
                        relatesTo = newRelationalDefaultContent
                ).toContent()
            }
        }
        return newContent
    }

    /**
     * Creates a thread event related to the already existing root event.
     */
    fun createThreadTextEvent(
            rootThreadEventId: String,
            roomId: String,
            text: CharSequence,
            msgType: String,
            autoMarkdown: Boolean,
            formattedText: String?,
            additionalContent: Content? = null,
    ): Event {
        val content = formattedText?.let { TextContent(text.toString(), it) } ?: createTextContent(text, autoMarkdown)
        return createEvent(
                roomId,
                EventType.MESSAGE,
                content.toThreadTextContent(
                        rootThreadEventId = rootThreadEventId,
                        latestThreadEventId = localEchoRepository.getLatestThreadEvent(rootThreadEventId),
                        msgType = msgType,
                        selfUserId = userId,
                ).toContent().plus(additionalContent.orEmpty())
        )
    }

    private fun dummyOriginServerTs(): Long {
        return clock.epochMillis()
    }

    fun createReplyTextContent(
            eventReplied: TimelineEvent,
            replyText: CharSequence,
            replyTextFormatted: CharSequence?,
            autoMarkdown: Boolean,
            rootThreadEventId: String? = null,
            showInThread: Boolean,
            @Suppress("UNUSED_PARAMETER")
            isRedactedEvent: Boolean = false,
            msgType: String = MessageType.MSGTYPE_TEXT,
    ): MessageContent? {
        val eventId = eventReplied.root.eventId ?: return null
        val repliedSenderId = eventReplied.root.senderId

        // Modern reply format: body / formatted_body contain only the user-typed reply text.
        // The receiving client renders the "in reply to" header dynamically from the cached
        // replied-to event via m.relates_to.m.in_reply_to. No legacy "> <@user:server>" quote
        // prefix in body, no <mx-reply> block in formatted_body — these duplicate the preview
        // and cause inconsistencies on edit. MSC3952 m.mentions carries the replied-to sender
        // alongside everyone pilled in the reply text.
        val plainBody = replyText.toString()
        // Honour an explicitly-provided formatted body verbatim (as sendFormattedTextMessage does);
        // otherwise only auto-format when markdown is on and genuinely produced formatting — a plain
        // reply must not carry format/formatted_body. A PGP armored body is never auto-formatted
        // (markdown would mangle the ciphertext); its formatted_body, if any, is supplied explicitly.
        // computeFormattedHtml handles both the markdown path and the span-only path (mentions, custom
        // emotes), so a reply carrying emote/pill spans gets a formatted_body even when markdown is off.
        val htmlBody = replyTextFormatted?.toString()
                ?: if (!isPgpArmoredBody(plainBody)) computeFormattedHtml(replyText, autoMarkdown) else null
        val isFormatted = htmlBody != null

        return MessageTextContent(
                msgType = msgType,
                format = if (isFormatted) MessageFormat.FORMAT_MATRIX_HTML else null,
                body = plainBody,
                formattedBody = if (isFormatted) htmlBody else null,
                relatesTo = generateReplyRelationContent(
                        eventId = eventId,
                        rootThreadEventId = rootThreadEventId,
                        showInThread = showInThread
                ),
                mentions = IntentionalMentions.build(
                        body = plainBody,
                        formattedBody = htmlBody,
                        extraUserIds = listOfNotNull(repliedSenderId),
                        selfUserId = userId,
                ),
        )
    }

    /**
     * Creates a reply to a regular timeline Event or a thread Event if needed.
     */
    fun createReplyTextEvent(
            roomId: String,
            eventReplied: TimelineEvent,
            replyText: CharSequence,
            replyTextFormatted: CharSequence?,
            autoMarkdown: Boolean,
            rootThreadEventId: String? = null,
            showInThread: Boolean,
            additionalContent: Content? = null,
            msgType: String = MessageType.MSGTYPE_TEXT,
    ): Event? {
        val content = createReplyTextContent(eventReplied, replyText, replyTextFormatted, autoMarkdown, rootThreadEventId, showInThread, msgType = msgType)
        return content?.let {
            createMessageEvent(roomId, it, additionalContent)
        }
    }

    private fun generateThreadRelationContent(rootThreadEventId: String) =
            RelationDefaultContent(
                    type = RelationType.THREAD,
                    eventId = rootThreadEventId,
                    isFallingBack = true,
                    inReplyTo = ReplyToContent(eventId = localEchoRepository.getLatestThreadEvent(rootThreadEventId)),
            )

    /**
     * Generates the appropriate relatesTo object for a reply event.
     * It can either be a regular reply or a reply within a thread
     * "m.relates_to": {
     *      "rel_type": "m.thread",
     *      "event_id": "$thread_root",
     *      "is_falling_back": false,
     *      "m.in_reply_to": {
     *          "event_id": "$event_target"
     *      }
     *  }
     */
    private fun generateReplyRelationContent(eventId: String, rootThreadEventId: String? = null, showInThread: Boolean): RelationDefaultContent =
            rootThreadEventId?.let {
                RelationDefaultContent(
                        type = RelationType.THREAD,
                        eventId = it,
                        isFallingBack = showInThread,
                        // False when is a rich reply from within a thread, and true when is a reply that should be visible from threads
                        inReplyTo = ReplyToContent(eventId = eventId)
                )
            } ?: RelationDefaultContent(null, null, ReplyToContent(eventId = eventId))

    private fun bodyForReply(timelineEvent: TimelineEvent, isRedactedEvent: Boolean = false): TextContent {
        val content = when (timelineEvent.root.getClearType()) {
            in EventType.POLL_END.values -> {
                // for end poll event, we use the content of the start poll event
                localEchoRepository
                        .getRelatedPollEvent(timelineEvent)
                        ?.getLastMessageContent()
                        ?: timelineEvent.getLastMessageContent()
            }
            else -> timelineEvent.getLastMessageContent()
        }
        return bodyForReply(content = content, isReply = timelineEvent.isReply(), isRedactedEvent = isRedactedEvent)
    }

    /**
     * Returns a TextContent used for the fallback event representation in a reply message.
     * In case of an edit of a reply the last content is not
     * himself a reply, but it will contain the fallbacks, so we have to trim them.
     */
    fun bodyForReply(content: MessageContent?, isReply: Boolean, isRedactedEvent: Boolean = false): TextContent {
        when (content?.msgType) {
            MessageType.MSGTYPE_EMOTE,
            MessageType.MSGTYPE_TEXT,
            MessageType.MSGTYPE_NOTICE -> {
                var formattedText: String? = null
                if (content is MessageContentWithFormattedBody) {
                    formattedText = content.matrixFormattedBody
                }
                return if (isRedactedEvent) {
                    TextContent("message removed.")
                } else if (isReply) {
                    TextContent(content.body, formattedText).removeInReplyFallbacks()
                } else {
                    TextContent(content.body, formattedText)
                }
            }
            MessageType.MSGTYPE_FILE -> return TextContent("sent a file.")
            MessageType.MSGTYPE_AUDIO -> return TextContent("sent an audio file.")
            MessageType.MSGTYPE_IMAGE -> return TextContent("sent an image.")
            MessageType.MSGTYPE_VIDEO -> return TextContent("sent a video.")
            MessageType.MSGTYPE_BEACON_INFO -> return TextContent(content.body.ensureNotEmpty() ?: "Live location")
            MessageType.MSGTYPE_POLL_START -> {
                return TextContent((content as? MessagePollContent)?.getBestPollCreationInfo()?.question?.getBestQuestion() ?: "")
            }
            MessageType.MSGTYPE_POLL_END -> return TextContent("Ended poll")
            else -> {
                return if (isRedactedEvent) {
                    TextContent("message removed.")
                } else TextContent(content?.body ?: "")
            }
        }
    }

    /**
     * Returns RFC5870 formatted geo uri 'geo:latitude,longitude;u=uncertainty' like 'geo:40.05,29.24;u=30'
     * Uncertainty of the location is in meters and not required.
     */
    private fun buildGeoUri(latitude: Double, longitude: Double, uncertainty: Double?): String {
        return buildString {
            append("geo:")
            append(latitude)
            append(",")
            append(longitude)
            uncertainty?.let {
                append(";u=")
                append(it)
            }
        }
    }

    /*
     * {
        "content": {
            "reason": "Spamming"
            },
            "event_id": "$143273582443PhrSn:domain.com",
            "origin_server_ts": 1432735824653,
            "redacts": "$fukweghifu23:localhost",
            "room_id": "!jEsUZKDJdhlrceRyVU:domain.com",
            "sender": "@example:domain.com",
            "type": "m.room.redaction",
            "unsigned": {
            "age": 1234
        }
    }
     */
    fun createRedactEvent(roomId: String, eventId: String, reason: String?, withRelTypes: List<String>? = null, additionalContent: Content? = null): Event {
        val localId = LocalEcho.createLocalEchoId()
        val content = if (reason != null || withRelTypes != null) {
            EventRedactBody(
                    reason = reason,
                    unstableWithRelTypes = withRelTypes,
            ).toContent().plus(additionalContent.orEmpty())
        } else {
            additionalContent
        }
        return Event(
                roomId = roomId,
                originServerTs = dummyOriginServerTs(),
                senderId = userId,
                eventId = localId,
                type = EventType.REDACTION,
                redacts = eventId,
                content = content,
                unsignedData = UnsignedData(age = null, transactionId = localId)
        )
    }

    fun createLocalEcho(event: Event) {
        checkNotNull(event.roomId) { "Your event should have a roomId" }
        localEchoRepository.createLocalEcho(event)
    }

    fun createQuotedTextEvent(
            roomId: String,
            quotedEvent: TimelineEvent,
            text: String,
            formattedText: String?,
            autoMarkdown: Boolean,
            rootThreadEventId: String?,
            additionalContent: Content? = null,
    ): Event {
        val messageContent = quotedEvent.getLastMessageContent()
        val formattedQuotedText = (messageContent as? MessageContentWithFormattedBody)?.formattedBody
        val textContent = createQuoteTextContent(messageContent?.body, formattedQuotedText, text, formattedText, autoMarkdown)
        return if (rootThreadEventId != null) {
            createMessageEvent(
                    roomId,
                    textContent.toThreadTextContent(
                            rootThreadEventId = rootThreadEventId,
                            latestThreadEventId = localEchoRepository.getLatestThreadEvent(rootThreadEventId),
                            msgType = MessageType.MSGTYPE_TEXT,
                            selfUserId = userId,
                    ),
                    additionalContent,
            )
        } else {
            createFormattedTextEvent(
                    roomId,
                    textContent,
                    MessageType.MSGTYPE_TEXT,
                    additionalContent,
            )
        }
    }

    private fun createQuoteTextContent(
            quotedText: String?,
            formattedQuotedText: String?,
            text: String,
            formattedText: String?,
            autoMarkdown: Boolean
    ): TextContent {
        val currentFormattedText = formattedText ?: if (autoMarkdown) {
            val parsed = markdownParser.parse(text, force = true, advanced = true)
            // If formattedText == text, formattedText is returned as null
            parsed.formattedText ?: parsed.text
        } else {
            text
        }
        val processedFormattedQuotedText = formattedQuotedText ?: quotedText

        val plainTextBody = buildString {
            val plainMessageParagraphs = quotedText?.split("\n\n".toRegex())?.dropLastWhile { it.isEmpty() }?.toTypedArray().orEmpty()
            plainMessageParagraphs.forEachIndexed { index, paragraph ->
                if (paragraph.isNotBlank()) {
                    append("> ")
                    append(paragraph)
                }

                if (index != plainMessageParagraphs.lastIndex) {
                    append("\n\n")
                }
            }
            append("\n\n")
            append(text)
        }
        val formattedTextBody = buildString {
            if (!processedFormattedQuotedText.isNullOrBlank()) {
                append("<blockquote>")
                append(processedFormattedQuotedText)
                append("</blockquote>")
            }
            append("<br/>")
            append(currentFormattedText)
        }
        return TextContent(plainTextBody, formattedTextBody)
    }

    companion object {
        // <mx-reply>
        //     <blockquote>
        //         <a href="https://matrix.to/#/!somewhere:domain.com/$event:domain.com">In reply to</a>
        //         <a href="https://matrix.to/#/@alice:example.org">@alice:example.org</a>
        //         <br />
        //         <!-- This is where the related event's HTML would be. -->
        //     </blockquote>
        // </mx-reply>
        // No whitespace because currently breaks temporary formatted text to Span
        const val REPLY_PATTERN = """<mx-reply><blockquote><a href="%s">In reply to</a> <a href="%s">%s</a><br />%s</blockquote></mx-reply>%s"""
        const val QUOTE_PATTERN = """<blockquote><p>%s</p></blockquote><p>%s</p>"""
    }
}
