/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.factory

import android.os.Build
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
import android.widget.ImageView
import androidx.annotation.StringRes
import dagger.Lazy
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.extensions.getVectorLastMessageContent
import im.vector.app.core.files.LocalFilesHelper
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.core.utils.containsOnlyEmojisAndEmotes
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
import im.vector.app.features.home.room.detail.timeline.item.BindingOptions
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
import im.vector.app.features.home.room.detail.timeline.pgp.PgpDecryptionRetriever
import im.vector.app.features.home.room.detail.timeline.render.EventTextRenderer
import im.vector.app.features.home.room.detail.timeline.render.ProcessBodyOfReplyToEventUseCase
import im.vector.app.features.home.room.detail.timeline.render.RichMessageBodyRenderer
import im.vector.app.features.home.room.detail.timeline.tools.createLinkMovementMethod
import im.vector.app.features.home.room.detail.timeline.tools.linkify
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.html.BodySegment
import im.vector.app.features.html.EmoteImageSpan
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.html.HiddenImageSpan
import im.vector.app.features.html.HtmlBodySegmenter
import im.vector.app.features.html.PillsPostProcessor
import im.vector.app.features.html.SpanUtils
import im.vector.app.features.html.VectorHtmlCompressor
import im.vector.app.features.location.INITIAL_MAP_ZOOM_IN_TIMELINE
import im.vector.app.features.location.UrlMapProvider
import im.vector.app.features.location.toLocationData
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.MediaContentRevealManager
import im.vector.app.features.media.VideoContentRenderer
import im.vector.app.features.media.isMediaHiddenInRoom
import im.vector.app.features.pgp.PgpUtils
import im.vector.app.features.redaction.preservation.PreservedMediaStore
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.voice.AudioWaveformView
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.core.utils.timer.Clock
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.attachments.toElementToDecrypt
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.content.EncryptedEventContent
import org.matrix.android.sdk.api.session.events.model.isThread
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.getTimelineEvent
import org.matrix.android.sdk.api.session.room.model.Membership
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
import org.matrix.android.sdk.api.session.room.model.message.getCaption
import org.matrix.android.sdk.api.session.room.model.message.getFileName
import org.matrix.android.sdk.api.session.room.model.message.getFileUrl
import org.matrix.android.sdk.api.session.room.model.message.getFormattedCaption
import org.matrix.android.sdk.api.session.room.model.message.getThumbnailUrl
import org.matrix.android.sdk.api.session.room.timeline.getRelationContent
import org.matrix.android.sdk.api.settings.LightweightSettingsStorage
import org.matrix.android.sdk.api.util.ContentUtils
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
        private val locationPinProvider: Lazy<LocationPinProvider>,
        private val vectorPreferences: VectorPreferences,
        private val preservedMediaStore: PreservedMediaStore,
        private val urlMapProvider: Lazy<UrlMapProvider>,
        private val liveLocationShareMessageItemFactory: Lazy<LiveLocationShareMessageItemFactory>,
        private val pollItemViewStateFactory: PollItemViewStateFactory,
        private val processBodyOfReplyToEventUseCase: ProcessBodyOfReplyToEventUseCase,
        private val richMessageBodyRenderer: RichMessageBodyRenderer,
        private val mediaContentRevealManager: MediaContentRevealManager,
        private val pgpDecryptor: im.vector.app.features.pgp.PgpDecryptor,
) {

    // MapLibre (and the whole location UI) is unavailable pre-Lollipop; never touch the location
    // builders there so their classes — and MapLibre's — are never loaded (they'd waste the tight
    // Dalvik LinearAlloc budget that already crashes room-open on ICS).
    private val locationSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP

    // TODO inject this properly?
    private var roomId: String = ""

    private val pillsPostProcessor by lazy {
        pillsPostProcessorFactory.create(roomId)
    }

    private val textRenderer by lazy {
        textRendererFactory.create(roomId)
    }

    fun create(params: TimelineItemFactoryParams): VectorEpoxyModel<*>? {
        val event = params.event
        val highlight = params.isHighlighted
        val callback = params.callback
        event.root.eventId ?: return null
        roomId = event.roomId
        val informationData = im.vector.app.core.utils.PerfTrace.time("create.infoData") { messageInformationDataFactory.create(params) }
        val threadDetails = if (params.isFromThreadTimeline()) null else event.root.threadDetails

        if (event.root.isRedacted()) {
            // message is redacted
            val attributes = messageItemAttributesFactory.create(null, informationData, callback, params.reactionsSummaryEvents, threadDetails)
            val redactedTextRes = if (event.root.getClearType() == EventType.REACTION) {
                CommonStrings.reaction_redacted
            } else {
                CommonStrings.event_redacted
            }
            return buildRedactedItem(attributes, highlight, redactedTextRes)
        }

        val messageContent = event.getVectorLastMessageContent()
        if (messageContent == null) {
            val malformedText = stringProvider.getString(CommonStrings.malformed_message)
            return defaultItemFactory.create(malformedText, informationData, highlight, callback, params.reactionsSummaryEvents)
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
        val attributes = im.vector.app.core.utils.PerfTrace.time("create.attributes") {
            messageItemAttributesFactory.create(messageContent, informationData, callback, params.reactionsSummaryEvents, threadDetails)
        }

        //        val all = event.root.toContent()
        //        val ev = all.toModel<Event>()
        val messageItem = im.vector.app.core.utils.PerfTrace.time("create.build.${messageContent.msgType}") { when (messageContent) {
            is MessageEmoteContent -> buildEmoteMessageItem(messageContent, informationData, highlight, callback, attributes)
            is MessageTextContent -> buildItemForTextContent(messageContent, informationData, highlight, callback, attributes)
            is MessageImageInfoContent -> buildImageMessageItem(messageContent, informationData, highlight, callback, attributes)
            is MessageNoticeContent -> buildNoticeMessageItem(messageContent, informationData, highlight, callback, attributes)
            is MessageVideoContent -> buildVideoMessageItem(messageContent, informationData, highlight, callback, attributes)
            is MessageFileContent -> buildFileMessageItem(messageContent, informationData, callback, highlight, attributes)
            is MessageAudioContent -> buildAudioContent(params, messageContent, informationData, highlight, attributes)
            is MessageVerificationRequestContent -> buildVerificationRequestMessageItem(messageContent, informationData, highlight, callback, attributes)
            is MessagePollContent -> buildPollItem(
                    messageContent, informationData, highlight, callback, attributes,
                    isEnded = false,
                    canInteract = params.partialState.roomSummary?.membership == Membership.JOIN,
            )
            is MessageEndPollContent -> buildEndedPollItem(event.getRelationContent()?.eventId, informationData, highlight, callback, attributes)
            is MessageLocationContent -> if (locationSupported) {
                buildLocationItem(messageContent, informationData, highlight, attributes)
            } else {
                buildNotHandledMessageItem(messageContent, informationData, highlight, callback, attributes)
            }
            is MessageBeaconInfoContent -> if (locationSupported) {
                liveLocationShareMessageItemFactory.get().create(event, highlight, attributes)
            } else {
                buildNotHandledMessageItem(messageContent, informationData, highlight, callback, attributes)
            }
            else -> buildNotHandledMessageItem(messageContent, informationData, highlight, callback, attributes)
        } }
        return messageItem?.apply {
            layout(informationData.messageLayout.layoutRes)
            (this as? AbsMessageItem<*>)?.let { item ->
                item.replyPreviewRetriever = callback?.getReplyPreviewRetriever()
                item.inReplyToClickCallback = callback
                if (item.movementMethod == null) {
                    item.movementMethod = createLinkMovementMethod(callback)
                }
            }
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
            urlMapProvider.get().buildStaticMapUrl(it, INITIAL_MAP_ZOOM_IN_TIMELINE, width, height)
        }

        val pinMatrixItem = if (locationContent.isSelfLocation()) informationData.matrixItem else null

        return MessageLocationItem_()
                .attributes(attributes)
                .locationUrl(locationUrl)
                .mapWidth(width)
                .mapHeight(height)
                .pinMatrixItem(pinMatrixItem)
                .locationPinProvider(locationPinProvider.get())
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
            canInteract: Boolean = true,
    ): PollItem {
        val pollViewState = pollItemViewStateFactory.create(
                pollContent = pollContent,
                pollResponseData = informationData.pollResponseAggregatedSummary,
                isSent = informationData.sendState.isSent(),
        ).let { if (canInteract) it else it.copy(canVote = false) }

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
        val renderedCaption = renderCaption(
                body = messageContent.getCaption(isReply).orEmpty(),
                formattedBody = messageContent.getFormattedCaption(isReply),
                informationData = informationData,
                callback = params.callback,
        )

        return MessageAudioItem_()
                .attributes(attributes)
                .filename(messageContent.getFileName())
                .duration(messageContent.audioInfo?.duration ?: 0)
                .playbackControlButtonClickListener(playbackControlButtonClickListener)
                .audioMessagePlaybackTracker(audioMessagePlaybackTracker)
                .izLocalFile(localFilesHelper.isLocalFile(fileUrl))
                .fileSize(messageContent.audioInfo?.size ?: 0L)
                .onSeek { params.callback?.onAudioSeekBarMovedTo(informationData.stableId, duration, it) }
                .mxcUrl(fileUrl)
                .contentUploadStateTrackerBinder(contentUploadStateTrackerBinder)
                .contentDownloadStateTrackerBinder(contentDownloadStateTrackerBinder)
                .highlighted(highlight)
                .leftGuideline(avatarSizeProvider.leftGuideline)
                .caption(renderedCaption?.epoxy)
                .captionBindingOptions(renderedCaption?.bindingOptions)
                .captionUseBigFont(renderedCaption?.useBigFont == true)
                .captionMarkwonPlugins(htmlRenderer.get().plugins)
                .captionMovementMethod(createLinkMovementMethod(params.callback))
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
            params.callback?.onVoiceControlButtonClicked(informationData.stableId, messageContent)
        }
    }

    private fun buildVoiceMessageItem(
            params: TimelineItemFactoryParams,
            messageContent: MessageAudioContent,
            informationData: MessageInformationData,
            highlight: Boolean,
            attributes: AbsMessageItem.Attributes
    ): BaseEventItem<*>? {
        val fileUrl = getAudioFileUrl(messageContent, informationData)
        val playbackControlButtonClickListener = createOnPlaybackButtonClickListener(messageContent, informationData, params)
        val isReply = messageContent.relatesTo?.inReplyTo?.eventId != null
        val renderedCaption = renderCaption(
                body = messageContent.getCaption(isReply).orEmpty(),
                formattedBody = messageContent.getFormattedCaption(isReply),
                informationData = informationData,
                callback = params.callback,
        )

        val waveformTouchListener: MessageVoiceItem.WaveformTouchListener = object : MessageVoiceItem.WaveformTouchListener {
            override fun onWaveformTouchedUp(percentage: Float) {
                val duration = messageContent.audioInfo?.duration ?: 0
                params.callback?.onVoiceWaveformTouchedUp(informationData.stableId, duration, percentage)
            }

            override fun onWaveformMovedTo(percentage: Float) {
                val duration = messageContent.audioInfo?.duration ?: 0
                params.callback?.onVoiceWaveformMovedTo(informationData.stableId, duration, percentage)
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
                .caption(renderedCaption?.epoxy)
                .captionBindingOptions(renderedCaption?.bindingOptions)
                .captionUseBigFont(renderedCaption?.useBigFont == true)
                .captionMarkwonPlugins(htmlRenderer.get().plugins)
                .captionMovementMethod(createLinkMovementMethod(params.callback))
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
        val renderedCaption = renderCaption(
                body = messageContent.getCaption(isReply).orEmpty(),
                formattedBody = messageContent.getFormattedCaption(isReply),
                informationData = informationData,
                callback = callback,
        )
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
                .caption(renderedCaption?.epoxy)
                .captionBindingOptions(renderedCaption?.bindingOptions)
                .captionUseBigFont(renderedCaption?.useBigFont == true)
                .captionMarkwonPlugins(htmlRenderer.get().plugins)
                .captionMovementMethod(createLinkMovementMethod(callback))
    }

    private fun buildAudioContent(
            params: TimelineItemFactoryParams,
            messageContent: MessageAudioContent,
            informationData: MessageInformationData,
            highlight: Boolean,
            attributes: AbsMessageItem.Attributes,
            // Voice messages (waveform UI) are KitKat+; below that, render as a plain audio file so the
            // MessageVoiceItem/waveform classes are never loaded (Dalvik LinearAlloc budget on ICS).
    ) = if (messageContent.voiceMessageIndicator != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
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

    private fun shouldHideMedia(informationData: MessageInformationData): Boolean {
        if (informationData.sentByMe) return false
        return isMediaHiddenInRoom(session.roomService().getRoomSummary(roomId), vectorPreferences)
    }

    /** The local copy kept for a revealed redaction, if the media was preserved. */
    private fun preservedMediaFor(informationData: MessageInformationData): java.io.File? {
        if (!informationData.isRevealedRedaction) return null
        return preservedMediaStore.fileFor(roomId, informationData.eventId).takeIf { it.isFile }
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
        val preservedMedia = preservedMediaFor(informationData)
        val data = ImageContentRenderer.Data(
                eventId = informationData.eventId,
                stableId = informationData.stableId,
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
                preservedFile = preservedMedia,
        )

        // MSC4230 settles it outright; otherwise only a GIF mimetype is a firm enough signal, while
        // webp/apng/png/jxl merely might be animated.
        val isAnimated = messageContent.info?.isAnimated
        val certainlyAnimated = isAnimated ?: (messageContent.mimeType == MimeTypes.Gif)
        val maybeAnimated = certainlyAnimated || (isAnimated == null && (
                messageContent.mimeType == MimeTypes.Webp ||
                        messageContent.mimeType == MimeTypes.Apng ||
                        messageContent.mimeType == MimeTypes.Png ||
                        messageContent.mimeType == MimeTypes.Jxl
                ))
        val autoplay = vectorPreferences.autoplayAnimatedImages()
        // Whether the badge actually shows is the item's call: it hides it while autoplay is on, since
        // the image is already moving, and shows it otherwise so a still animation reads as playable.
        val playable = certainlyAnimated

        val attachmentContent = messageContent as? MessageWithAttachmentContent
        val isReply = attachmentContent?.relatesTo?.inReplyTo?.eventId != null
        val renderedCaption = attachmentContent?.let { mc ->
            renderCaption(
                    body = mc.getCaption(isReply).orEmpty(),
                    formattedBody = mc.getFormattedCaption(isReply),
                    informationData = informationData,
                    callback = callback,
            )
        }

        val hideMedia = shouldHideMedia(informationData)

        return MessageImageVideoItem_()
                .attributes(attributes)
                .leftGuideline(avatarSizeProvider.leftGuideline)
                .imageContentRenderer(imageContentRenderer)
                .contentUploadStateTrackerBinder(contentUploadStateTrackerBinder)
                .hideMedia(hideMedia)
                .hiddenMediaSolidColor(vectorPreferences.useSolidColorForHiddenMedia())
                .mediaRevealManager(mediaContentRevealManager)
                .playable(playable)
                .highlighted(highlight)
                .mediaData(data)
                .caption(renderedCaption?.epoxy)
                .captionBindingOptions(renderedCaption?.bindingOptions)
                .captionUseBigFont(renderedCaption?.useBigFont == true)
                .captionMarkwonPlugins(htmlRenderer.get().plugins)
                .captionMovementMethod(createLinkMovementMethod(callback))
                .apply {
                    // A still-sending event may have no entry in the media viewer's list yet,
                    // which would open the viewer on the wrong item; keep it untappable until sent.
                    // Media that will never load has nothing for the viewer to show either.
                    if (!informationData.sendState.isSending()) {
                        clickListener { view ->
                            // Checked at tap time, not bind time: the load is still in flight when
                            // this item binds, and a later bind may well succeed.
                            val imageView = view as? ImageView
                            when {
                                imageView != null && imageContentRenderer.isRetrying(imageView) -> Unit
                                imageContentRenderer.isFailed(data) -> imageView?.let { imageContentRenderer.retry(it) }
                                else -> callback?.onImageMessageClicked(messageContent, data, view, emptyList())
                            }
                        }
                    }
                    if (messageContent.msgType == MessageType.MSGTYPE_STICKER_LOCAL) {
                        mode(ImageContentRenderer.Mode.STICKER)
                    } else if (maybeAnimated && autoplay) {
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
        // Size the box to the thumbnail, not the video: a mismatched one would be letterboxed.
        val thumbnailInfo = messageContent.videoInfo?.thumbnailInfo?.takeIf { it.width > 0 && it.height > 0 }
        val thumbnailData = ImageContentRenderer.Data(
                eventId = informationData.eventId,
                stableId = informationData.stableId,
                filename = mediaFilename,
                mimeType = messageContent.mimeType,
                url = messageContent.videoInfo?.getThumbnailUrl(),
                elementToDecrypt = messageContent.videoInfo?.thumbnailFile?.toElementToDecrypt(),
                height = thumbnailInfo?.height ?: messageContent.videoInfo?.height,
                maxHeight = maxHeight,
                width = thumbnailInfo?.width ?: messageContent.videoInfo?.width,
                maxWidth = maxWidth,
                allowNonMxcUrls = informationData.sendState.isSending(),
                blurHash = messageContent.videoInfo?.blurHash,
                // The preserved copy is the full video, which also serves as its own poster frame.
                preservedFile = preservedMediaFor(informationData),
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
        val renderedCaption = renderCaption(
                body = messageContent.getCaption(isReply).orEmpty(),
                formattedBody = messageContent.getFormattedCaption(isReply),
                informationData = informationData,
                callback = callback,
        )

        return MessageImageVideoItem_()
                .leftGuideline(avatarSizeProvider.leftGuideline)
                .attributes(attributes)
                .imageContentRenderer(imageContentRenderer)
                .contentUploadStateTrackerBinder(contentUploadStateTrackerBinder)
                .hideMedia(shouldHideMedia(informationData))
                .hiddenMediaSolidColor(vectorPreferences.useSolidColorForHiddenMedia())
                .mediaRevealManager(mediaContentRevealManager)
                .playable(true)
                .mediaDurationMs(messageContent.videoInfo?.duration ?: 0)
                .highlighted(highlight)
                .mediaData(thumbnailData)
                .caption(renderedCaption?.epoxy)
                .captionBindingOptions(renderedCaption?.bindingOptions)
                .captionUseBigFont(renderedCaption?.useBigFont == true)
                .captionMarkwonPlugins(htmlRenderer.get().plugins)
                .captionMovementMethod(createLinkMovementMethod(callback))
                .apply {
                    if (!informationData.sendState.isSending()) {
                        clickListener { view -> callback?.onVideoMessageClicked(messageContent, videoData, view.findViewById(R.id.messageThumbnailView)) }
                    }
                }
    }

    private fun buildItemForTextContent(
            messageContent: MessageTextContent,
            informationData: MessageInformationData,
            highlight: Boolean,
            callback: TimelineEventController.Callback?,
            attributes: AbsMessageItem.Attributes,
    ): VectorEpoxyModel<*>? {
        // PGP-over-plaintext: replace the armored body with the decrypted plaintext (or a
        // placeholder while OpenKeychain works / on failure). The lock badge is rendered
        // separately in the shield slot — this only touches the bubble text.
        if (informationData.isPgp) {
            // Prefer the encrypted formatted_body: once decrypted it's the rendered HTML the sender
            // intended (the body only carries the markdown source). Goes through the retriever under a
            // distinct cache key so it doesn't collide with the body and invalidation stays targeted to
            // this event. Only a clean armored block qualifies — QuickMedia's formatted_body is the
            // body's ciphertext re-encoded with <br>/<mx-reply>, which can't decrypt; fall back to body.
            val formattedArmored = messageContent.matrixFormattedBody
                    ?.takeIf { PgpUtils.bodyContainsPgp(it) && !it.contains("<br") && !it.contains("<mx-reply") }
            val retriever = callback?.getPgpDecryptionRetriever()
            if (formattedArmored != null && retriever != null) {
                val armored = PgpUtils.extractArmoredBlock(formattedArmored) ?: formattedArmored
                val state = retriever.getOrRequest(informationData.eventId, armored, cacheKey = informationData.stableId + PGP_FORMATTED_CACHE_SUFFIX)
                if (state is PgpDecryptionRetriever.State.Decrypted) {
                    return buildFormattedTextItem(state.text, informationData, highlight, callback, attributes)
                }
            }
            buildPgpBody(messageContent.body, informationData, callback)?.let { pgpBody ->
                return buildMessageTextItem(pgpBody, false, informationData, highlight, callback, attributes)
            }
        }
        val matrixFormattedBody = messageContent.matrixFormattedBody
        val replyToContent = messageContent.relatesTo?.inReplyTo
        return if (matrixFormattedBody != null) {
            buildFormattedTextItem(matrixFormattedBody, informationData, highlight, callback, attributes)
        } else {
            // Strip any legacy "> <@user:server> …" reply fallback prefix from the plain body; the
            // replied-to preview is rendered separately by InReplyToView.
            val body = if (replyToContent?.eventId != null) ContentUtils.extractUsefulTextFromReply(messageContent.body) else messageContent.body
            buildMessageTextItem(body, false, informationData, highlight, callback, attributes)
        }
    }

    // Returns the text to show for a PGP message, or null to fall through to normal rendering
    // (auto-decrypt disabled -> show the raw armored body as-is).
    private fun buildPgpBody(
            body: String,
            informationData: MessageInformationData,
            callback: TimelineEventController.Callback?,
    ): CharSequence? {
        val retriever = callback?.getPgpDecryptionRetriever() ?: return null
        val armored = PgpUtils.extractArmoredBlock(body) ?: body
        return when (val state = retriever.getOrRequest(informationData.eventId, armored, cacheKey = informationData.stableId)) {
            null -> null
            is PgpDecryptionRetriever.State.Decrypted -> state.text
            is PgpDecryptionRetriever.State.Pending -> stringProvider.getString(CommonStrings.pgp_decrypting)
            is PgpDecryptionRetriever.State.NeedsInteraction -> stringProvider.getString(CommonStrings.encrypted_message)
            is PgpDecryptionRetriever.State.Failed -> stringProvider.getString(CommonStrings.pgp_decryption_failed)
        }
    }

    private fun buildFormattedTextItem(
            matrixFormattedBody: String,
            informationData: MessageInformationData,
            highlight: Boolean,
            callback: TimelineEventController.Callback?,
            attributes: AbsMessageItem.Attributes,
            noticeStyle: Boolean = false,
    ): MessageTextItem? {
        // Strip any embedded legacy `<mx-reply>`; the replied-to preview is rendered separately by InReplyToView.
        val bareBody = processBodyOfReplyToEventUseCase.stripExistingMxReply(matrixFormattedBody)
        val compressed = htmlCompressor.compress(bareBody)
        val containsTable = compressed.contains("<table", ignoreCase = true)
        val containsCodeBlock = compressed.contains("<pre", ignoreCase = true)
        val renderedBody = (htmlRenderer.get().render(compressed, pillsPostProcessor) as Spanned).trimUncoveredWhitespace()

        val segments = if (containsTable || containsCodeBlock) {
            // Only take the rich path when a top-level table/code block was actually extracted; a nested
            // one stays inline (single Html segment) and keeps the normal footered text rendering.
            HtmlBodySegmenter.segment(compressed).takeIf { segs -> segs.any { it !is BodySegment.Html } }
        } else {
            null
        }
        // When the room hides media, inline <img>/emoticons are blocked behind grey placeholders
        // until the message is tapped to reveal (handled in MessageTextItem via mediaRevealManager).
        val blockedBody = if (segments == null && shouldHideMedia(informationData) && compressed.contains("<img", ignoreCase = true)) {
            buildBlockedInlineImagesBody(compressed)
        } else {
            null
        }
        return buildMessageTextItem(
                renderedBody,
                true,
                informationData,
                highlight,
                callback,
                attributes,
                bodySegments = segments,
                noticeStyle = noticeStyle,
                richReplyHeader = null,
                blockedBody = blockedBody,
        )
    }

    private fun buildBlockedInlineImagesBody(compressedHtml: String): CharSequence {
        val blockedHtml = compressedHtml.replace(IMG_TAG_REGEX, OBJECT_REPLACEMENT_STRING)
        val rendered = (htmlRenderer.get().render(blockedHtml, pillsPostProcessor) as? Spanned) ?: return ""
        val ssb = SpannableStringBuilder(rendered)
        val gray = colorProvider.getColorFromAttribute(im.vector.lib.ui.styles.R.attr.vctr_content_quaternary)
        var idx = ssb.indexOf(OBJECT_REPLACEMENT_CHAR)
        while (idx >= 0) {
            ssb.setSpan(HiddenImageSpan(gray), idx, idx + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            idx = ssb.indexOf(OBJECT_REPLACEMENT_CHAR, idx + 1)
        }
        return ssb.trimUncoveredWhitespace()
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

    private data class RenderedCaption(
            val epoxy: EpoxyCharSequence,
            val bindingOptions: BindingOptions,
            val useBigFont: Boolean,
    )

    /**
     * Renders an optional user-typed caption attached to a media event (MSC2530) into the values
     * for the media Epoxy item's caption attributes — or `null` when there's no caption to render.
     *
     * Uses the same Markwon HTML / textRenderer / linkify / annotateWithEdited pipeline as
     * regular text messages so pills, links, edits, emoji and the big emoji-only rendering all
     * work identically.
     */
    private fun renderCaption(
            body: String,
            formattedBody: String?,
            informationData: MessageInformationData,
            callback: TimelineEventController.Callback?,
    ): RenderedCaption? {
        if (body.isEmpty()) return null
        // PGP: a captioned media's caption may be an armored block — show the decrypted plaintext
        // (and ignore the armored formatted_body).
        val pgpCaption = pgpDecryptor.peekDecryptedBody(body)
        val effectiveBody = pgpCaption ?: body
        val effectiveFormatted = if (pgpCaption != null) null else formattedBody
        val initialBody: CharSequence = if (effectiveFormatted != null) {
            val compressed = htmlCompressor.compress(effectiveFormatted)
            val raw = htmlRenderer.get().render(compressed, pillsPostProcessor) as? Spanned
            raw?.trimUncoveredWhitespace() ?: effectiveBody
        } else {
            effectiveBody
        }
        val rendered = textRenderer.render(initialBody)
        val bindingOptions = spanUtils.getBindingOptions(rendered)
        val linkified = rendered.linkify(callback)
        val emoteRanges = (linkified as? Spanned)
                ?.let { spanned -> spanned.getSpans(0, spanned.length, EmoteImageSpan::class.java).map { spanned.getSpanStart(it) until spanned.getSpanEnd(it) } }
                .orEmpty()
        val final = if (informationData.hasBeenEdited) {
            annotateWithEdited(linkified, callback, informationData)
        } else {
            linkified
        }
        return RenderedCaption(
                epoxy = final.prepareForDisplay().toEpoxyCharSequence(),
                bindingOptions = bindingOptions,
                useBigFont = containsOnlyEmojisAndEmotes(linkified, emoteRanges, MAX_NUMBER_OF_EMOJI_FOR_BIG_FONT),
        )
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
            blockedBody: CharSequence? = null,
    ): MessageTextItem? {
        val renderedBody = textRenderer.render(body)
        val bindingOptions = im.vector.app.core.utils.PerfTrace.time("build.text.bindingOptions") { spanUtils.getBindingOptions(renderedBody) }
        val linkifiedBody = im.vector.app.core.utils.PerfTrace.time("build.text.linkify") { renderedBody.linkify(callback) }

        val blockedRendered = blockedBody?.let { textRenderer.render(it) }
        val blockedLinkified = blockedRendered?.linkify(callback)

        // A message of only emoji and/or custom emotes (+ spaces) renders large, like the emoji-only rule.
        val emoteRanges = (linkifiedBody as? Spanned)
                ?.let { spanned -> spanned.getSpans(0, spanned.length, EmoteImageSpan::class.java).map { spanned.getSpanStart(it) until spanned.getSpanEnd(it) } }
                .orEmpty()

        return MessageTextItem_()
                .message(
                        (if (informationData.hasBeenEdited) {
                            annotateWithEdited(linkifiedBody, callback, informationData)
                        } else {
                            linkifiedBody
                        }).prepareForDisplay().toEpoxyCharSequence()
                )
                .useBigFont(containsOnlyEmojisAndEmotes(linkifiedBody, emoteRanges, MAX_NUMBER_OF_EMOJI_FOR_BIG_FONT))
                .bindingOptions(bindingOptions)
                .markwonPlugins(htmlRenderer.get().plugins)
                .searchForPills(isFormatted)
                .previewUrlRetriever(callback?.getPreviewUrlRetriever())
                .imageContentRenderer(imageContentRenderer)
                .previewUrlCallback(callback)
                .noticeStyle(noticeStyle)
                .leftGuideline(avatarSizeProvider.leftGuideline)
                .attributes(attributes)
                .highlighted(highlight)
                .movementMethod(createLinkMovementMethod(callback))
                .apply {
                    if (blockedLinkified != null) {
                        blockedMessage(blockedLinkified.toEpoxyCharSequence())
                        blockedBindingOptions(spanUtils.getBindingOptions(blockedLinkified))
                        mediaRevealManager(mediaContentRevealManager)
                    }
                    if (bodySegments != null) {
                        bodySegments(bodySegments)
                        richBodyRenderer(richMessageBodyRenderer)
                        htmlPostProcessors(arrayOf<EventHtmlRenderer.PostProcessor>(pillsPostProcessor))
                        richReplyHeader(richReplyHeader)
                        urlClickCallback(callback)
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
            buildFormattedTextItem(matrixFormattedBody, informationData, highlight, callback, attributes, noticeStyle = true)
        } else {
            val body = if (replyToContent?.eventId != null) ContentUtils.extractUsefulTextFromReply(messageContent.body) else messageContent.body
            buildMessageTextItem(body, false, informationData, highlight, callback, attributes, noticeStyle = true)
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
                        (if (informationData.hasBeenEdited) {
                            annotateWithEdited(message, callback, informationData)
                        } else {
                            message
                        }).prepareForDisplay().toEpoxyCharSequence()
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

    private fun MessageContentWithFormattedBody.getHtmlBody(): CharSequence {
        return matrixFormattedBody
                ?.let { htmlCompressor.compress(it) }
                ?.let { htmlRenderer.get().render(it, pillsPostProcessor) }
                ?: body
    }

    private fun buildRedactedItem(
            attributes: AbsMessageItem.Attributes,
            highlight: Boolean,
            @StringRes redactedTextRes: Int,
    ): RedactedMessageItem? {
        return RedactedMessageItem_()
                .layout(attributes.informationData.messageLayout.layoutRes)
                .leftGuideline(avatarSizeProvider.leftGuideline)
                .attributes(attributes)
                .redactedTextRes(redactedTextRes)
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
        private val IMG_TAG_REGEX = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
        private const val OBJECT_REPLACEMENT_CHAR = '￼'
        private const val OBJECT_REPLACEMENT_STRING = "￼"
        private const val PGP_FORMATTED_CACHE_SUFFIX = "\u0000fmt"
    }
}
