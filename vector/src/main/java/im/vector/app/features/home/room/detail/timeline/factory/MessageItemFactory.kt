/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.factory

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.view.View
import dagger.Lazy
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.extensions.getVectorLastMessageContent
import im.vector.app.core.files.LocalFilesHelper
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.core.utils.containsOnlyEmojis
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.helper.AudioMessagePlaybackTracker
import im.vector.app.features.home.room.detail.timeline.helper.AvatarSizeProvider
import im.vector.app.features.home.room.detail.timeline.helper.ContentDownloadStateTrackerBinder
import im.vector.app.features.home.room.detail.timeline.helper.ContentUploadStateTrackerBinder
import im.vector.app.features.home.room.detail.timeline.helper.LocationPinProvider
import im.vector.app.features.home.room.detail.timeline.helper.MessageInformationDataFactory
import im.vector.app.features.home.room.detail.timeline.helper.MessageItemAttributesFactory
import im.vector.app.features.home.room.detail.timeline.helper.TimelineMediaSizeProvider
import im.vector.app.features.home.room.detail.timeline.item.AbsMessageItem
import im.vector.app.features.home.room.detail.timeline.item.BaseEventItem
import im.vector.app.features.home.room.detail.timeline.item.MessageAudioItem
import im.vector.app.features.home.room.detail.timeline.item.MessageAudioItem_
import im.vector.app.features.home.room.detail.timeline.item.MessageFileItem
import im.vector.app.features.home.room.detail.timeline.item.MessageFileItem_
import im.vector.app.features.home.room.detail.timeline.item.MessageImageVideoItem
import im.vector.app.features.home.room.detail.timeline.item.MessageImageVideoItem_
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.home.room.detail.timeline.item.MessageLocationItem
import im.vector.app.features.home.room.detail.timeline.item.MessageLocationItem_
import im.vector.app.features.home.room.detail.timeline.item.MessageTextItem
import im.vector.app.features.home.room.detail.timeline.item.MessageTextItem_
import im.vector.app.features.home.room.detail.timeline.item.MessageVoiceItem
import im.vector.app.features.home.room.detail.timeline.item.MessageVoiceItem_
import im.vector.app.features.home.room.detail.timeline.item.PollItem
import im.vector.app.features.home.room.detail.timeline.item.PollItem_
import im.vector.app.features.home.room.detail.timeline.item.RedactedMessageItem
import im.vector.app.features.home.room.detail.timeline.item.RedactedMessageItem_
import im.vector.app.features.home.room.detail.timeline.item.VerificationRequestItem
import im.vector.app.features.home.room.detail.timeline.item.VerificationRequestItem_
import im.vector.app.features.home.room.detail.timeline.render.EventTextRenderer
import im.vector.app.features.home.room.detail.timeline.render.ProcessBodyOfReplyToEventUseCase
import im.vector.app.features.home.room.detail.timeline.render.RichMessageBodyRenderer
import im.vector.app.features.html.BodySegment
import im.vector.app.features.html.HtmlBodySegmenter
import im.vector.app.features.home.room.detail.timeline.tools.createLinkMovementMethod
import im.vector.app.features.home.room.detail.timeline.tools.linkify
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.html.PillsPostProcessor
import im.vector.app.features.html.SpanUtils
import im.vector.app.features.html.VectorHtmlCompressor
import im.vector.app.features.location.INITIAL_MAP_ZOOM_IN_TIMELINE
import im.vector.app.features.location.UrlMapProvider
import im.vector.app.features.location.toLocationData
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.VideoContentRenderer
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.voice.AudioWaveformView
import im.vector.app.features.home.room.detail.timeline.item.BindingOptions
import im.vector.app.features.voicebroadcast.isVoiceBroadcast
import im.vector.app.features.voicebroadcast.model.MessageVoiceBroadcastInfoContent
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.core.utils.timer.Clock
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.attachments.toElementToDecrypt
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.content.EncryptedEventContent
import org.matrix.android.sdk.api.session.events.model.isThread
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.getTimelineEvent
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageBeaconInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContentWithFormattedBody
import org.matrix.android.sdk.api.session.room.model.message.MessageEmoteContent
import org.matrix.android.sdk.api.session.room.model.message.MessageEndPollContent
import org.matrix.android.sdk.api.session.room.model.message.MessageFileContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageLocationContent
import org.matrix.android.sdk.api.session.room.model.message.MessageNoticeContent
import org.matrix.android.sdk.api.session.room.model.message.MessagePollContent
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.MessageVerificationRequestContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.model.message.asMessageAudioEvent
import org.matrix.android.sdk.api.session.room.model.message.getCaption
import org.matrix.android.sdk.api.session.room.model.message.getFileName
import org.matrix.android.sdk.api.session.room.model.message.getFileUrl
import org.matrix.android.sdk.api.session.room.model.message.getFormattedCaption
import org.matrix.android.sdk.api.session.room.model.message.getMentionHint
import org.matrix.android.sdk.api.session.room.model.message.getThumbnailUrl
import org.matrix.android.sdk.api.session.room.model.relation.ReplyToContent
import org.matrix.android.sdk.api.session.room.timeline.getRelationContent
import org.matrix.android.sdk.api.settings.LightweightSettingsStorage
import org.matrix.android.sdk.api.util.MimeTypes
import javax.inject.Inject

class MessageItemFactory @Inject constructor(
        private val localFilesHelper: LocalFilesHelper,
        private val colorProvider: ColorProvider,
        private val dimensionConverter: DimensionConverter,
        private val timelineMediaSizeProvider: TimelineMediaSizeProvider,
        private val htmlRenderer: Lazy<EventHtmlRenderer>,
        private val htmlCompressor: VectorHtmlCompressor,
        private val textRendererFactory: EventTextRenderer.Factory,
        private val stringProvider: StringProvider,
        private val imageContentRenderer: ImageContentRenderer,
        private val messageInformationDataFactory: MessageInformationDataFactory,
        private val messageItemAttributesFactory: MessageItemAttributesFactory,
        private val contentUploadStateTrackerBinder: ContentUploadStateTrackerBinder,
        private val contentDownloadStateTrackerBinder: ContentDownloadStateTrackerBinder,
        private val defaultItemFactory: DefaultItemFactory,
        private val noticeItemFactory: NoticeItemFactory,
        private val avatarSizeProvider: AvatarSizeProvider,
        private val pillsPostProcessorFactory: PillsPostProcessor.Factory,
        private val lightweightSettingsStorage: LightweightSettingsStorage,
        private val spanUtils: SpanUtils,
        private val session: Session,
        private val clock: Clock,
        private val audioMessagePlaybackTracker: AudioMessagePlaybackTracker,
        private val locationPinProvider: LocationPinProvider,
        private val vectorPreferences: VectorPreferences,
        private val urlMapProvider: UrlMapProvider,
        private val liveLocationShareMessageItemFactory: LiveLocationShareMessageItemFactory,
        private val pollItemViewStateFactory: PollItemViewStateFactory,
        private val voiceBroadcastItemFactory: VoiceBroadcastItemFactory,
        private val processBodyOfReplyToEventUseCase: ProcessBodyOfReplyToEventUseCase,
        private val richMessageBodyRenderer: RichMessageBodyRenderer,
) {

    // TODO inject this properly?
    private var roomId: String = ""

    private val pillsPostProcessor by lazy {
        pillsPostProcessorFactory.create(roomId)
    }

    private val textRenderer by lazy {
        textRendererFactory.create(roomId)
    }

    private val useRichTextEditorStyle: Boolean
        get() = vectorPreferences.isRichTextEditorEnabled()

    fun create(params: TimelineItemFactoryParams): VectorEpoxyModel<*>? {
        val event = params.event
        val highlight = params.isHighlighted
        val callback = params.callback
        event.root.eventId ?: return null
        roomId = event.roomId
        val informationData = messageInformationDataFactory.create(params)
        val threadDetails = if (params.isFromThreadTimeline()) null else event.root.threadDetails

        if (event.root.isRedacted()) {
            // message is redacted
            val attributes = messageItemAttributesFactory.create(null, informationData, callback, params.reactionsSummaryEvents, threadDetails)
            return buildRedactedItem(attributes, highlight)
        }

        val messageContent = event.getVectorLastMessageContent()
        if (messageContent == null) {
            val malformedText = stringProvider.getString(CommonStrings.malformed_message)
            return defaultItemFactory.create(malformedText, informationData, highlight, callback)
        }
        if (messageContent.relatesTo?.type == RelationType.REPLACE ||
                event.isEncrypted() && event.root.content.toModel<EncryptedEventContent>()?.relatesTo?.type == RelationType.REPLACE
        ) {
            // This is an edit event, we should display it when debugging as a notice event
            return noticeItemFactory.create(params)
        }

        if (lightweightSettingsStorage.areThreadMessagesEnabled() && !params.isFromThreadTimeline() && event.root.isThread()) {
            // This is a thread event and we will [debug] display it when we are in the main timeline
            return noticeItemFactory.create(params)
        }

        // always hide summary when we are on thread timeline
        val attributes = messageItemAttributesFactory.create(messageContent, informationData, callback, params.reactionsSummaryEvents, threadDetails)

        //        val all = event.root.toContent()
        //        val ev = all.toModel<Event>()
        val messageItem = when (messageContent) {
            is MessageEmoteContent -> buildEmoteMessageItem(messageContent, informationData, highlight, callback, attributes)
            is MessageTextContent -> buildItemForTextContent(messageContent, informationData, highlight, callback, attributes)
            is MessageImageInfoContent -> buildImageMessageItem(messageContent, informationData, highlight, callback, attributes)
            is MessageNoticeContent -> buildNoticeMessageItem(messageContent, informationData, highlight, callback, attributes)
            is MessageVideoContent -> buildVideoMessageItem(messageContent, informationData, highlight, callback, attributes)
            is MessageFileContent -> buildFileMessageItem(messageContent, informationData, callback, highlight, attributes)
            is MessageAudioContent -> buildAudioContent(params, messageContent, informationData, highlight, attributes)
            is MessageVerificationRequestContent -> buildVerificationRequestMessageItem(messageContent, informationData, highlight, callback, attributes)
            is MessagePollContent -> buildPollItem(messageContent, informationData, highlight, callback, attributes, isEnded = false)
            is MessageEndPollContent -> buildEndedPollItem(event.getRelationContent()?.eventId, informationData, highlight, callback, attributes)
            is MessageLocationContent -> buildLocationItem(messageContent, informationData, highlight, attributes)
            is MessageBeaconInfoContent -> liveLocationShareMessageItemFactory.create(event, highlight, attributes)
            is MessageVoiceBroadcastInfoContent -> voiceBroadcastItemFactory.create(params, messageContent, highlight, attributes)
            else -> buildNotHandledMessageItem(messageContent, informationData, highlight, callback, attributes)
        }
        return messageItem?.apply {
            layout(informationData.messageLayout.layoutRes)
        }
    }

    private fun buildLocationItem(
            locationContent: MessageLocationContent,
            informationData: MessageInformationData,
            highlight: Boolean,
            attributes: AbsMessageItem.Attributes,
    ): MessageLocationItem? {
        val width = timelineMediaSizeProvider.getMaxSize().first
        val height = dimensionConverter.dpToPx(MESSAGE_LOCATION_ITEM_HEIGHT_IN_DP)

        val locationUrl = locationContent.toLocationData()?.let {
            urlMapProvider.buildStaticMapUrl(it, INITIAL_MAP_ZOOM_IN_TIMELINE, width, height)
        }

        val pinMatrixItem = if (locationContent.isSelfLocation()) informationData.matrixItem else null

        return MessageLocationItem_()
                .attributes(attributes)
                .locationUrl(locationUrl)
                .mapWidth(width)
                .mapHeight(height)
                .pinMatrixItem(pinMatrixItem)
                .locationPinProvider(locationPinProvider)
                .highlighted(highlight)
                .leftGuideline(avatarSizeProvider.leftGuideline)
    }

    private fun buildPollItem(
            pollContent: MessagePollContent,
            informationData: MessageInformationData,
            highlight: Boolean,
            callback: TimelineEventController.Callback?,
            attributes: AbsMessageItem.Attributes,
            isEnded: Boolean,
    ): PollItem {
        val pollViewState = pollItemViewStateFactory.create(
                pollContent = pollContent,
                pollResponseData = informationData.pollResponseAggregatedSummary,
                isSent = informationData.sendState.isSent(),
        )

        return PollItem_()
                .attributes(attributes)
                .eventId(informationData.eventId)
                .pollTitle(createPollQuestion(informationData, pollViewState.question, callback))
                .canVote(pollViewState.canVote)
                .votesStatus(pollViewState.votesStatus)
                .optionViewStates(pollViewState.optionViewStates.orEmpty())
                .edited(informationData.hasBeenEdited)
                .ended(isEnded)
                .highlighted(highlight)
                .leftGuideline(avatarSizeProvider.leftGuideline)
                .callback(callback)
    }

    private fun buildEndedPollItem(
            pollStartEventId: String?,
            informationData: MessageInformationData,
            highlight: Boolean,
            callback: TimelineEventController.Callback?,
            attributes: AbsMessageItem.Attributes,
    ): PollItem {
        val pollStartEvent = if (pollStartEventId?.isNotEmpty() == true) {
            session.roomService().getRoom(roomId)?.getTimelineEvent(pollStartEventId)
        } else {
            null
        }

        val editedContent = pollStartEvent?.annotations?.editSummary?.latestEdit?.getClearContent()?.toModel<MessagePollContent>()?.newContent
        val latestContent = editedContent ?: pollStartEvent?.root?.getClearContent()
        val pollContent = latestContent?.toModel<MessagePollContent>()

        return if (pollContent == null) {
            val title = stringProvider.getString(CommonStrings.message_reply_to_ended_poll_preview).toEpoxyCharSequence()
            PollItem_()
                    .attributes(attributes)
                    .eventId(informationData.eventId)
                    .pollTitle(title)
                    .optionViewStates(emptyList())
                    .edited(informationData.hasBeenEdited)
                    .ended(true)
                    .hasContent(false)
                    .highlighted(highlight)
                    .leftGuideline(avatarSizeProvider.leftGuideline)
                    .callback(callback)
        } else {
            buildPollItem(
                    pollContent,
                    informationData,
                    highlight,
                    callback,
                    attributes,
                    isEnded = true,
            )
        }
    }

    private fun createPollQuestion(
            informationData: MessageInformationData,
            question: String,
            callback: TimelineEventController.Callback?,
    ) = if (informationData.hasBeenEdited) {
        annotateWithEdited(question, callback, informationData)
    } else {
        question
    }.toEpoxyCharSequence()

    private fun buildAudioMessageItem(
            params: TimelineItemFactoryParams,
            messageContent: MessageAudioContent,
            informationData: MessageInformationData,
            highlight: Boolean,
            attributes: AbsMessageItem.Attributes
    ): MessageAudioItem {
        val fileUrl = getAudioFileUrl(messageContent, informationData)
        val playbackControlButtonClickListener = createOnPlaybackButtonClickListener(messageContent, informationData, params)
        val duration = messageContent.audioInfo?.duration ?: 0
        val isReply = messageContent.relatesTo?.inReplyTo?.eventId != null
        val (captionEpoxy, captionBindingOptions) = renderCaption(
                body = messageContent.getCaption(isReply).orEmpty(),
                formattedBody = messageContent.getFormattedCaption(isReply),
                informationData = informationData,
                callback = params.callback,
        ) ?: (null to null)
        val (replyHeaderEpoxy, replyHeaderBindingOptions) = renderReplyHeader(
                replyToContent = messageContent.relatesTo?.inReplyTo,
                mentionHint = messageContent.getMentionHint(),
                callback = params.callback,
        ) ?: (null to null)

        return MessageAudioItem_()
                .attributes(attributes)
                .filename(messageContent.getFileName())
                .duration(messageContent.audioInfo?.duration ?: 0)
                .playbackControlButtonClickListener(playbackControlButtonClickListener)
                .audioMessagePlaybackTracker(audioMessagePlaybackTracker)
                .izLocalFile(localFilesHelper.isLocalFile(fileUrl))
                .fileSize(messageContent.audioInfo?.size ?: 0L)
                .onSeek { params.callback?.onAudioSeekBarMovedTo(informationData.eventId, duration, it) }
                .mxcUrl(fileUrl)
                .contentUploadStateTrackerBinder(contentUploadStateTrackerBinder)
                .contentDownloadStateTrackerBinder(contentDownloadStateTrackerBinder)
                .highlighted(highlight)
                .leftGuideline(avatarSizeProvider.leftGuideline)
                .caption(captionEpoxy)
                .captionBindingOptions(captionBindingOptions)
                .captionMovementMethod(createLinkMovementMethod(params.callback))
                .replyHeader(replyHeaderEpoxy)
                .replyHeaderBindingOptions(replyHeaderBindingOptions)
    }

    private fun getAudioFileUrl(
            messageContent: MessageAudioContent,
            informationData: MessageInformationData,
    ) = messageContent.getFileUrl()?.let {
        if (informationData.sentByMe && !informationData.sendState.isSent()) {
            it
        } else {
            it.takeIf { it.isMxcUrl() }
        }
    } ?: ""

    private fun createOnPlaybackButtonClickListener(
            messageContent: MessageAudioContent,
            informationData: MessageInformationData,
            params: TimelineItemFactoryParams,
    ) = object : ClickListener {
        override fun invoke(view: View) {
            params.callback?.onVoiceControlButtonClicked(informationData.eventId, messageContent)
        }
    }

    private fun buildVoiceMessageItem(
            params: TimelineItemFactoryParams,
            messageContent: MessageAudioContent,
            informationData: MessageInformationData,
            highlight: Boolean,
            attributes: AbsMessageItem.Attributes
    ): BaseEventItem<*>? {
        // Do not display voice broadcast messages
        if (params.event.root.asMessageAudioEvent().isVoiceBroadcast()) {
            return noticeItemFactory.create(params)
        }

        val fileUrl = getAudioFileUrl(messageContent, informationData)
        val playbackControlButtonClickListener = createOnPlaybackButtonClickListener(messageContent, informationData, params)
        val isReply = messageContent.relatesTo?.inReplyTo?.eventId != null
        val (captionEpoxy, captionBindingOptions) = renderCaption(
                body = messageContent.getCaption(isReply).orEmpty(),
                formattedBody = messageContent.getFormattedCaption(isReply),
                informationData = informationData,
                callback = params.callback,
        ) ?: (null to null)
        val (replyHeaderEpoxy, replyHeaderBindingOptions) = renderReplyHeader(
                replyToContent = messageContent.relatesTo?.inReplyTo,
                mentionHint = messageContent.getMentionHint(),
                callback = params.callback,
        ) ?: (null to null)

        val waveformTouchListener: MessageVoiceItem.WaveformTouchListener = object : MessageVoiceItem.WaveformTouchListener {
            override fun onWaveformTouchedUp(percentage: Float) {
                val duration = messageContent.audioInfo?.duration ?: 0
                params.callback?.onVoiceWaveformTouchedUp(informationData.eventId, duration, percentage)
            }

            override fun onWaveformMovedTo(percentage: Float) {
                val duration = messageContent.audioInfo?.duration ?: 0
                params.callback?.onVoiceWaveformMovedTo(informationData.eventId, duration, percentage)
            }
        }

        return MessageVoiceItem_()
                .attributes(attributes)
                .duration(messageContent.audioWaveformInfo?.duration ?: 0)
                .waveform(messageContent.audioWaveformInfo?.waveform?.toFft().orEmpty())
                .playbackControlButtonClickListener(playbackControlButtonClickListener)
                .waveformTouchListener(waveformTouchListener)
                .audioMessagePlaybackTracker(audioMessagePlaybackTracker)
                .izLocalFile(localFilesHelper.isLocalFile(fileUrl))
                .mxcUrl(fileUrl)
                .contentUploadStateTrackerBinder(contentUploadStateTrackerBinder)
                .contentDownloadStateTrackerBinder(contentDownloadStateTrackerBinder)
                .highlighted(highlight)
                .leftGuideline(avatarSizeProvider.leftGuideline)
                .caption(captionEpoxy)
                .captionBindingOptions(captionBindingOptions)
                .captionMovementMethod(createLinkMovementMethod(params.callback))
                .replyHeader(replyHeaderEpoxy)
                .replyHeaderBindingOptions(replyHeaderBindingOptions)
    }

    private fun buildVerificationRequestMessageItem(
            messageContent: MessageVerificationRequestContent,
            @Suppress("UNUSED_PARAMETER")
            informationData: MessageInformationData,
            highlight: Boolean,
            callback: TimelineEventController.Callback?,
            attributes: AbsMessageItem.Attributes,
    ): VerificationRequestItem? {
        // If this request is not sent by me or sent to me, we should ignore it in timeline
        val myUserId = session.myUserId
        if (informationData.senderId != myUserId && messageContent.toUserId != myUserId) {
            return null
        }

        val otherUserId = if (informationData.sentByMe) messageContent.toUserId else informationData.senderId
        val otherUserName = if (informationData.sentByMe) {
            session.roomService().getRoomMember(messageContent.toUserId, roomId)?.displayName
        } else {
            informationData.memberName
        }
        return VerificationRequestItem_()
                .attributes(
                        VerificationRequestItem.Attributes(
                                otherUserId = otherUserId,
                                otherUserName = otherUserName.toString(),
                                referenceId = informationData.eventId,
                                informationData = informationData,
                                avatarRenderer = attributes.avatarRenderer,
                                messageColorProvider = attributes.messageColorProvider,
                                itemLongClickListener = attributes.itemLongClickListener,
                                itemClickListener = attributes.itemClickListener,
                                reactionPillCallback = attributes.reactionPillCallback,
                                readReceiptsCallback = attributes.readReceiptsCallback,
                                emojiTypeFace = attributes.emojiTypeFace,
                                reactionsSummaryEvents = attributes.reactionsSummaryEvents,
                        )
                )
                .clock(clock)
                .callback(callback)
                .highlighted(highlight)
                .leftGuideline(avatarSizeProvider.leftGuideline)
    }

    private fun buildFileMessageItem(
            messageContent: MessageFileContent,
            informationData: MessageInformationData,
            callback: TimelineEventController.Callback?,
            highlight: Boolean,
            attributes: AbsMessageItem.Attributes,
    ): MessageFileItem {
        val mxcUrl = messageContent.getFileUrl() ?: ""
        val isReply = messageContent.relatesTo?.inReplyTo?.eventId != null
        val (captionEpoxy, captionBindingOptions) = renderCaption(
                body = messageContent.getCaption(isReply).orEmpty(),
                formattedBody = messageContent.getFormattedCaption(isReply),
                informationData = informationData,
                callback = callback,
        ) ?: (null to null)
        val (replyHeaderEpoxy, replyHeaderBindingOptions) = renderReplyHeader(
                replyToContent = messageContent.relatesTo?.inReplyTo,
                mentionHint = messageContent.getMentionHint(),
                callback = callback,
        ) ?: (null to null)
        return MessageFileItem_()
                .attributes(attributes)
                .leftGuideline(avatarSizeProvider.leftGuideline)
                .izLocalFile(localFilesHelper.isLocalFile(messageContent.getFileUrl()))
                .izDownloaded(session.fileService().isFileInCache(messageContent))
                .mxcUrl(mxcUrl)
                .contentUploadStateTrackerBinder(contentUploadStateTrackerBinder)
                .contentDownloadStateTrackerBinder(contentDownloadStateTrackerBinder)
                .highlighted(highlight)
                .filename(messageContent.getFileName())
                .iconRes(R.drawable.ic_paperclip)
                .caption(captionEpoxy)
                .captionBindingOptions(captionBindingOptions)
                .captionMovementMethod(createLinkMovementMethod(callback))
                .replyHeader(replyHeaderEpoxy)
                .replyHeaderBindingOptions(replyHeaderBindingOptions)
    }

    private fun buildAudioContent(
            params: TimelineItemFactoryParams,
            messageContent: MessageAudioContent,
            informationData: MessageInformationData,
            highlight: Boolean,
            attributes: AbsMessageItem.Attributes,
    ) = if (messageContent.voiceMessageIndicator != null) {
        buildVoiceMessageItem(params, messageContent, informationData, highlight, attributes)
    } else {
        buildAudioMessageItem(params, messageContent, informationData, highlight, attributes)
    }

    private fun buildNotHandledMessageItem(
            messageContent: MessageContent,
            informationData: MessageInformationData,
            highlight: Boolean,
            callback: TimelineEventController.Callback?,
            attributes: AbsMessageItem.Attributes
    ): MessageTextItem? {
        // For compatibility reason we should display the body
        return buildMessageTextItem(
                messageContent.body,
                false,
                informationData,
                highlight,
                callback,
                attributes,
        )
    }

    private fun buildImageMessageItem(
            messageContent: MessageImageInfoContent,
            informationData: MessageInformationData,
            highlight: Boolean,
            callback: TimelineEventController.Callback?,
            attributes: AbsMessageItem.Attributes,
    ): MessageImageVideoItem? {
        val (maxWidth, maxHeight) = timelineMediaSizeProvider.getMaxSize()
        // MSC2530: `filename` is the real on-disk name; `body` becomes the user-typed caption.
        // Use it for download/save filename and render `body` (+ `formatted_body`) as a caption
        // below the image. Use it for the renderer cache key too so identical avatars share.
        val mediaFilename = (messageContent as? MessageWithAttachmentContent)?.getFileName() ?: messageContent.body
        val data = ImageContentRenderer.Data(
                eventId = informationData.eventId,
                filename = mediaFilename,
                mimeType = messageContent.mimeType,
                url = messageContent.getFileUrl(),
                elementToDecrypt = messageContent.encryptedFileInfo?.toElementToDecrypt(),
                height = messageContent.info?.height,
                maxHeight = maxHeight,
                width = messageContent.info?.width,
                maxWidth = maxWidth,
                allowNonMxcUrls = informationData.sendState.isSending(),
                blurHash = messageContent.info?.blurHash,
        )

        val playable = messageContent.mimeType == MimeTypes.Gif
        // don't show play button because detecting animated webp/apng isn't possible via mimetype
        val playableIfAutoplay = playable ||
                messageContent.mimeType == MimeTypes.Webp ||
                messageContent.mimeType == MimeTypes.Apng ||
                messageContent.mimeType == MimeTypes.Png

        val attachmentContent = messageContent as? MessageWithAttachmentContent
        val isReply = attachmentContent?.relatesTo?.inReplyTo?.eventId != null
        val (captionEpoxy, captionBindingOptions) = attachmentContent?.let { mc ->
            renderCaption(
                    body = mc.getCaption(isReply).orEmpty(),
                    formattedBody = mc.getFormattedCaption(isReply),
                    informationData = informationData,
                    callback = callback,
            )
        } ?: (null to null)
        val (replyHeaderEpoxy, replyHeaderBindingOptions) = renderReplyHeader(
                replyToContent = attachmentContent?.relatesTo?.inReplyTo,
                mentionHint = attachmentContent?.getMentionHint(),
                callback = callback,
        ) ?: (null to null)

        return MessageImageVideoItem_()
                .attributes(attributes)
                .leftGuideline(avatarSizeProvider.leftGuideline)
                .imageContentRenderer(imageContentRenderer)
                .contentUploadStateTrackerBinder(contentUploadStateTrackerBinder)
                .playable(playable)
                .highlighted(highlight)
                .mediaData(data)
                .caption(captionEpoxy)
                .captionBindingOptions(captionBindingOptions)
                .captionMovementMethod(createLinkMovementMethod(callback))
                .replyHeader(replyHeaderEpoxy)
                .replyHeaderBindingOptions(replyHeaderBindingOptions)
                .apply {
                    if (messageContent.msgType == MessageType.MSGTYPE_STICKER_LOCAL) {
                        mode(ImageContentRenderer.Mode.STICKER)
                    }
                    clickListener { view ->
                        callback?.onImageMessageClicked(messageContent, data, view, emptyList())
                    }
                }.apply {
                    if (playableIfAutoplay && vectorPreferences.autoplayAnimatedImages()) {
                        mode(ImageContentRenderer.Mode.ANIMATED_THUMBNAIL)
                    }
                }
    }

    private fun buildVideoMessageItem(
            messageContent: MessageVideoContent,
            informationData: MessageInformationData,
            highlight: Boolean,
            callback: TimelineEventController.Callback?,
            attributes: AbsMessageItem.Attributes,
    ): MessageImageVideoItem? {
        val (maxWidth, maxHeight) = timelineMediaSizeProvider.getMaxSize()
        val mediaFilename = messageContent.getFileName()
        val thumbnailData = ImageContentRenderer.Data(
                eventId = informationData.eventId,
                filename = mediaFilename,
                mimeType = messageContent.mimeType,
                url = messageContent.videoInfo?.getThumbnailUrl(),
                elementToDecrypt = messageContent.videoInfo?.thumbnailFile?.toElementToDecrypt(),
                height = messageContent.videoInfo?.height,
                maxHeight = maxHeight,
                width = messageContent.videoInfo?.width,
                maxWidth = maxWidth,
                allowNonMxcUrls = informationData.sendState.isSending(),
                blurHash = messageContent.videoInfo?.blurHash,
        )

        val videoData = VideoContentRenderer.Data(
                eventId = informationData.eventId,
                filename = mediaFilename,
                mimeType = messageContent.mimeType,
                url = messageContent.getFileUrl(),
                elementToDecrypt = messageContent.encryptedFileInfo?.toElementToDecrypt(),
                thumbnailMediaData = thumbnailData
        )

        val isReply = messageContent.relatesTo?.inReplyTo?.eventId != null
        val (captionEpoxy, captionBindingOptions) = renderCaption(
                body = messageContent.getCaption(isReply).orEmpty(),
                formattedBody = messageContent.getFormattedCaption(isReply),
                informationData = informationData,
                callback = callback,
        ) ?: (null to null)
        val (replyHeaderEpoxy, replyHeaderBindingOptions) = renderReplyHeader(
                replyToContent = messageContent.relatesTo?.inReplyTo,
                mentionHint = messageContent.getMentionHint(),
                callback = callback,
        ) ?: (null to null)

        return MessageImageVideoItem_()
                .leftGuideline(avatarSizeProvider.leftGuideline)
                .attributes(attributes)
                .imageContentRenderer(imageContentRenderer)
                .contentUploadStateTrackerBinder(contentUploadStateTrackerBinder)
                .playable(true)
                .highlighted(highlight)
                .mediaData(thumbnailData)
                .caption(captionEpoxy)
                .captionBindingOptions(captionBindingOptions)
                .captionMovementMethod(createLinkMovementMethod(callback))
                .replyHeader(replyHeaderEpoxy)
                .replyHeaderBindingOptions(replyHeaderBindingOptions)
                .clickListener { view -> callback?.onVideoMessageClicked(messageContent, videoData, view.findViewById(R.id.messageThumbnailView)) }
    }

    private fun buildItemForTextContent(
            messageContent: MessageTextContent,
            informationData: MessageInformationData,
            highlight: Boolean,
            callback: TimelineEventController.Callback?,
            attributes: AbsMessageItem.Attributes,
    ): VectorEpoxyModel<*>? {
        val matrixFormattedBody = messageContent.matrixFormattedBody
        val replyToContent = messageContent.relatesTo?.inReplyTo
        // MSC3952: modern clients put the user being replied to in m.mentions.user_ids[0].
        // Use it as a hint for the sender link when the referenced event isn't loaded yet.
        val mentionHint = messageContent.mentions?.userIds?.firstOrNull()
        return if (matrixFormattedBody != null) {
            buildFormattedTextItem(matrixFormattedBody, informationData, highlight, callback, attributes, replyToContent, mentionHint)
        } else if (replyToContent?.eventId != null) {
            // Newer clients may send only m.in_reply_to without a formatted_body + legacy
            // fallback. Route through the formatted path with an HTML-escaped copy of the
            // plain body so the use case can inject a synthetic <mx-reply> block (or a
            // "Loading…" placeholder) and the user still sees a clickable reply indicator.
            val escapedBody = messageContent.body
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\n", "<br />")
            buildFormattedTextItem(escapedBody, informationData, highlight, callback, attributes, replyToContent, mentionHint)
        } else {
            buildMessageTextItem(messageContent.body, false, informationData, highlight, callback, attributes)
        }
    }

    private fun buildFormattedTextItem(
            matrixFormattedBody: String,
            informationData: MessageInformationData,
            highlight: Boolean,
            callback: TimelineEventController.Callback?,
            attributes: AbsMessageItem.Attributes,
            replyToContent: ReplyToContent?,
            mentionHint: String? = null,
            noticeStyle: Boolean = false,
    ): MessageTextItem? {
        // Render the actual body with any embedded legacy `<mx-reply>` stripped — the synthetic
        // reply header (if any) is rendered in a second Markwon pass and prepended below. This
        // avoids MxReplyTagHandler's positional surgery on the SpannableBuilder, which mangled
        // span positions of links / inline code that followed the mx-reply block in a single
        // combined pass (resulting in literal HTML being shown in the timeline).
        val bareBody = processBodyOfReplyToEventUseCase.stripExistingMxReply(matrixFormattedBody)
        val compressed = htmlCompressor.compress(bareBody)
        val containsTable = compressed.contains("<table", ignoreCase = true)
        val renderedBody = (htmlRenderer.get().render(compressed, pillsPostProcessor) as Spanned).trimUncoveredWhitespace()

        val replyHeader: CharSequence? = if (replyToContent?.eventId != null) {
            renderReplyHeader(replyToContent, mentionHint, callback)?.first?.charSequence
        } else {
            null
        }
        val segments = if (containsTable) {
            HtmlBodySegmenter.segment(compressed)
        } else {
            null
        }
        // For the plain-TextView path the header is concatenated into `message`. For the rich
        // body path the message field is ignored, so we pass the header separately so the
        // renderer can prepend it as its own TextView above the segments.
        val finalBody: CharSequence = if (replyHeader != null && segments == null) {
            val ssb = SpannableStringBuilder()
                    .append(replyHeader)
                    .append("\n\n")
            val headerEnd = ssb.length
            ssb.append(renderedBody)
            if (noticeStyle) {
                // The TextView's base colour is secondary for notice styling; restore primary
                // over the reply-header range so it doesn't appear muted. Apply only on the
                // gaps where the header has no inline colour of its own, so usernames and
                // other coloured spans are preserved verbatim.
                applyDefaultColorOnGaps(
                        ssb,
                        rangeStart = 0,
                        rangeEnd = headerEnd,
                        color = colorProvider.getColorFromAttribute(im.vector.lib.ui.styles.R.attr.vctr_content_primary),
                )
            }
            ssb
        } else {
            renderedBody
        }
        return buildMessageTextItem(
                finalBody,
                true,
                informationData,
                highlight,
                callback,
                attributes,
                bodySegments = segments,
                noticeStyle = noticeStyle,
                richReplyHeader = if (segments != null) replyHeader else null,
        )
    }

    // Trim outer whitespace, but not whitespace covered by a block span — those spans rely
    // on it for line-height / leading-margin math.
    private fun Spanned.trimUncoveredWhitespace(): CharSequence {
        fun Char.isTrimable() = this == '\n' || this == ' ' || this == '\t'
        val coveredRanges =
                getSpans(0, length, LeadingMarginSpan::class.java).map { getSpanStart(it) to getSpanEnd(it) } +
                        getSpans(0, length, LineHeightSpan::class.java).map { getSpanStart(it) to getSpanEnd(it) }
        fun covered(at: Int) = coveredRanges.any { (s, e) -> at in s until e }
        var start = 0
        while (start < length && this[start].isTrimable() && !covered(start)) start++
        var end = length
        while (end > start && this[end - 1].isTrimable() && !covered(end - 1)) end--
        return if (start == 0 && end == length) this else subSequence(start, end)
    }

    /**
     * Renders an optional user-typed caption attached to a media event (MSC2530). Returns
     * `(epoxyCharSequence, bindingOptions)` ready to feed to the media Epoxy item's
     * `caption(...)` / `captionBindingOptions(...)` attributes — or `null` when there's no
     * caption to render.
     *
     * Uses the same Markwon HTML / textRenderer / linkify / annotateWithEdited pipeline as
     * regular text messages so pills, links, edits and emoji all work identically.
     */
    private fun renderCaption(
            body: String,
            formattedBody: String?,
            informationData: MessageInformationData,
            callback: TimelineEventController.Callback?,
    ): Pair<EpoxyCharSequence, BindingOptions>? {
        if (body.isEmpty()) return null
        val initialBody: CharSequence = if (formattedBody != null) {
            val compressed = htmlCompressor.compress(formattedBody)
            val raw = htmlRenderer.get().render(compressed, pillsPostProcessor) as? Spanned
            raw?.trimUncoveredWhitespace() ?: body
        } else {
            body
        }
        val rendered = textRenderer.render(initialBody)
        val bindingOptions = spanUtils.getBindingOptions(rendered)
        val linkified = rendered.linkify(callback)
        val final = if (informationData.hasBeenEdited) {
            annotateWithEdited(linkified, callback, informationData)
        } else {
            linkified
        }
        return final.toEpoxyCharSequence() to bindingOptions
    }

    /**
     * Renders just the `<mx-reply>` block (sender + preview of replied-to event) for a media
     * event that's a modern reply. Shown above the media. Returns null if there's no reply.
     */
    private fun renderReplyHeader(
            replyToContent: ReplyToContent?,
            mentionHint: String?,
            callback: TimelineEventController.Callback?,
    ): Pair<EpoxyCharSequence, BindingOptions>? {
        if (replyToContent?.eventId == null) return null
        val html = processBodyOfReplyToEventUseCase.execute(roomId, "", replyToContent, mentionHint)
        if (html.isEmpty()) return null
        val compressed = htmlCompressor.compress(html)
        val rendered = (htmlRenderer.get().render(compressed, pillsPostProcessor) as? Spanned) ?: return null
        // Markwon's blockquote handling leaves trailing newlines after the mx-reply block;
        // trim them so there's no big gap between the reply header and the media below.
        val trimmed = rendered.trimEnd('\n', ' ')
        val processed = textRenderer.render(trimmed)
        val bindingOptions = spanUtils.getBindingOptions(processed)
        val linkified = processed.linkify(callback)
        return linkified.toEpoxyCharSequence() to bindingOptions
    }

    private fun buildMessageTextItem(
            body: CharSequence,
            isFormatted: Boolean,
            informationData: MessageInformationData,
            highlight: Boolean,
            callback: TimelineEventController.Callback?,
            attributes: AbsMessageItem.Attributes,
            bodySegments: List<BodySegment>? = null,
            noticeStyle: Boolean = false,
            richReplyHeader: CharSequence? = null,
    ): MessageTextItem? {
        val renderedBody = textRenderer.render(body)
        val bindingOptions = spanUtils.getBindingOptions(renderedBody)
        val linkifiedBody = renderedBody.linkify(callback)

        return MessageTextItem_()
                .message(
                        if (informationData.hasBeenEdited) {
                            annotateWithEdited(linkifiedBody, callback, informationData)
                        } else {
                            linkifiedBody
                        }.toEpoxyCharSequence()
                )
                .useBigFont(linkifiedBody.length <= MAX_NUMBER_OF_EMOJI_FOR_BIG_FONT * 2 && containsOnlyEmojis(linkifiedBody.toString()))
                .bindingOptions(bindingOptions)
                .markwonPlugins(htmlRenderer.get().plugins)
                .searchForPills(isFormatted)
                .previewUrlRetriever(callback?.getPreviewUrlRetriever())
                .imageContentRenderer(imageContentRenderer)
                .previewUrlCallback(callback)
                .useRichTextEditorStyle(useRichTextEditorStyle)
                .noticeStyle(noticeStyle)
                .leftGuideline(avatarSizeProvider.leftGuideline)
                .attributes(attributes)
                .highlighted(highlight)
                .movementMethod(createLinkMovementMethod(callback))
                .apply {
                    if (bodySegments != null) {
                        bodySegments(bodySegments)
                        richBodyRenderer(richMessageBodyRenderer)
                        htmlPostProcessors(arrayOf<EventHtmlRenderer.PostProcessor>(pillsPostProcessor))
                        richReplyHeader(richReplyHeader)
                    }
                }
    }

    private fun annotateWithEdited(
            linkifiedBody: CharSequence,
            callback: TimelineEventController.Callback?,
            informationData: MessageInformationData,
    ): Spannable {
        val spannable = SpannableStringBuilder()
        spannable.append(linkifiedBody)
        val editedSuffix = stringProvider.getString(CommonStrings.edited_suffix)
        spannable.append(" ").append(editedSuffix)
        val color = colorProvider.getColorFromAttribute(im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
        val editStart = spannable.lastIndexOf(editedSuffix)
        val editEnd = editStart + editedSuffix.length
        spannable.setSpan(
                ForegroundColorSpan(color),
                editStart,
                editEnd,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE
        )

        // Note: text size is set to 14sp
        spannable.setSpan(
                AbsoluteSizeSpan(dimensionConverter.spToPx(13)),
                editStart,
                editEnd,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE
        )

        spannable.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        callback?.onEditedDecorationClicked(informationData)
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        // nop
                    }
                },
                editStart,
                editEnd,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE
        )
        return spannable
    }

    private fun buildNoticeMessageItem(
            messageContent: MessageNoticeContent,
            informationData: MessageInformationData,
            highlight: Boolean,
            callback: TimelineEventController.Callback?,
            attributes: AbsMessageItem.Attributes,
    ): MessageTextItem? {
        val matrixFormattedBody = messageContent.matrixFormattedBody
        val replyToContent = messageContent.relatesTo?.inReplyTo
        return if (matrixFormattedBody != null) {
            buildFormattedTextItem(matrixFormattedBody, informationData, highlight, callback, attributes, replyToContent, mentionHint = null, noticeStyle = true)
        } else if (replyToContent?.eventId != null) {
            val escapedBody = messageContent.body
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\n", "<br />")
            buildFormattedTextItem(escapedBody, informationData, highlight, callback, attributes, replyToContent, mentionHint = null, noticeStyle = true)
        } else {
            buildMessageTextItem(messageContent.body, false, informationData, highlight, callback, attributes, noticeStyle = true)
        }
    }

    private fun buildEmoteMessageItem(
            messageContent: MessageEmoteContent,
            informationData: MessageInformationData,
            highlight: Boolean,
            callback: TimelineEventController.Callback?,
            attributes: AbsMessageItem.Attributes,
    ): MessageTextItem? {
        val formattedBody = SpannableStringBuilder()
        formattedBody.append("* ${informationData.memberName} ")
        formattedBody.append(messageContent.getHtmlBody())
        val bindingOptions = spanUtils.getBindingOptions(formattedBody)
        val message = formattedBody.linkify(callback)

        return MessageTextItem_()
                .message(
                        if (informationData.hasBeenEdited) {
                            annotateWithEdited(message, callback, informationData)
                        } else {
                            message
                        }.toEpoxyCharSequence()
                )
                .bindingOptions(bindingOptions)
                .leftGuideline(avatarSizeProvider.leftGuideline)
                .previewUrlRetriever(callback?.getPreviewUrlRetriever())
                .imageContentRenderer(imageContentRenderer)
                .previewUrlCallback(callback)
                .attributes(attributes)
                .highlighted(highlight)
                .movementMethod(createLinkMovementMethod(callback))
    }

    /** Add a ForegroundColorSpan over [rangeStart, rangeEnd) only on character runs not already
     *  covered by an existing ForegroundColorSpan, so inline colours from `<font color>` etc.
     *  remain authoritative. */
    private fun applyDefaultColorOnGaps(sb: SpannableStringBuilder, rangeStart: Int, rangeEnd: Int, color: Int) {
        if (rangeEnd <= rangeStart) return
        val occupied = sb.getSpans(rangeStart, rangeEnd, ForegroundColorSpan::class.java)
                .map { sb.getSpanStart(it).coerceAtLeast(rangeStart) to sb.getSpanEnd(it).coerceAtMost(rangeEnd) }
                .filter { it.first < it.second }
                .sortedBy { it.first }
        var cursor = rangeStart
        for ((s, e) in occupied) {
            if (s > cursor) sb.setSpan(ForegroundColorSpan(color), cursor, s, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (e > cursor) cursor = e
        }
        if (cursor < rangeEnd) sb.setSpan(ForegroundColorSpan(color), cursor, rangeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun MessageContentWithFormattedBody.getHtmlBody(): CharSequence {
        return matrixFormattedBody
                ?.let { htmlCompressor.compress(it) }
                ?.let { htmlRenderer.get().render(it, pillsPostProcessor) }
                ?: body
    }

    private fun buildRedactedItem(
            attributes: AbsMessageItem.Attributes,
            highlight: Boolean,
    ): RedactedMessageItem? {
        return RedactedMessageItem_()
                .layout(attributes.informationData.messageLayout.layoutRes)
                .leftGuideline(avatarSizeProvider.leftGuideline)
                .attributes(attributes)
                .highlighted(highlight)
    }

    private fun List<Int?>?.toFft(): List<Int>? {
        return this
                ?.filterNotNull()
                ?.map {
                    // Value comes from AudioWaveformView.MAX_FFT, and 1024 is the max value in the Matrix spec
                    it * AudioWaveformView.MAX_FFT / 1024
                }
    }

    companion object {
        private const val MAX_NUMBER_OF_EMOJI_FOR_BIG_FONT = 5
        const val MESSAGE_LOCATION_ITEM_HEIGHT_IN_DP = 200
    }
}
