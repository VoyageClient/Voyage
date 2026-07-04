/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import android.view.View
import im.vector.app.features.home.room.detail.RoomDetailAction
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.home.room.detail.timeline.item.ReadReceiptData
import im.vector.app.features.home.room.detail.timeline.pgp.PgpDecryptionRetriever
import im.vector.app.features.home.room.detail.timeline.reply.ReplyPreviewRetriever
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlRetriever
import im.vector.app.features.media.AttachmentData
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.VideoContentRenderer
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import javax.inject.Inject

/**
 * A [TimelineEventController.Callback] where every interaction defaults to a no-op, for hosts that
 * render timeline items outside the room timeline (e.g. search results). Only the retrievers —
 * which the item factories genuinely need — are wired, via [retrievers]. Hosts delegate to this
 * and override just the interactions they support, so new callback methods only need a default
 * added here instead of in every host.
 */
class StubTimelineEventCallback @Inject constructor() : TimelineEventController.Callback {

    lateinit var retrievers: TimelineRetrievers

    override fun getPreviewUrlRetriever(): PreviewUrlRetriever = retrievers.previewUrlRetriever

    override fun getPgpDecryptionRetriever(): PgpDecryptionRetriever = retrievers.pgpDecryptionRetriever

    override fun getReplyPreviewRetriever(): ReplyPreviewRetriever = retrievers.replyPreviewRetriever

    override fun onEventCellClicked(informationData: MessageInformationData, messageContent: Any?, view: View, isRootThreadEvent: Boolean) {}
    override fun onEventLongClicked(informationData: MessageInformationData, messageContent: Any?, view: View): Boolean = false
    override fun onImageMessageClicked(
            messageImageContent: MessageImageInfoContent,
            mediaData: ImageContentRenderer.Data,
            view: View,
            inMemory: List<AttachmentData>
    ) {}

    override fun onVideoMessageClicked(messageVideoContent: MessageVideoContent, mediaData: VideoContentRenderer.Data, view: View) {}
    override fun onVoiceControlButtonClicked(eventId: String, messageAudioContent: MessageAudioContent) {}
    override fun onVoiceWaveformTouchedUp(eventId: String, duration: Int, percentage: Float) {}
    override fun onVoiceWaveformMovedTo(eventId: String, duration: Int, percentage: Float) {}
    override fun onAudioSeekBarMovedTo(eventId: String, duration: Int, percentage: Float) {}
    override fun onThreadSummaryClicked(eventId: String, isRootThreadEvent: Boolean): Boolean = false
    override fun onRepliedToEventClicked(sourceEventId: String?, targetEventId: String) {}
    override fun onUrlClicked(url: String, title: String): Boolean = false
    override fun onUrlLongClicked(url: String): Boolean = true
    override fun onLoadMore(direction: Timeline.Direction) {}
    override fun onEventInvisible(event: TimelineEvent) {}
    override fun onEventVisible(event: TimelineEvent) {}
    override fun onRoomCreateLinkClicked(url: String) {}
    override fun onEncryptedMessageClicked(informationData: MessageInformationData, view: View) {}
    override fun onEditedDecorationClicked(informationData: MessageInformationData) {}
    override fun onTimelineItemAction(itemAction: RoomDetailAction) {}
    override fun onAddMoreReaction(event: TimelineEvent) {}
    override fun onClickOnReactionPill(informationData: MessageInformationData, reaction: String, on: Boolean) {}
    override fun onLongClickOnReactionPill(informationData: MessageInformationData, reaction: String) {}
    override fun onAvatarClicked(informationData: MessageInformationData) {}
    override fun onMemberNameClicked(informationData: MessageInformationData) {}
    override fun onReadReceiptsClicked(readReceipts: List<ReadReceiptData>) {}
    override fun onReadMarkerVisible() {}
    override fun onPreviewUrlClicked(url: String) {}
    override fun onPreviewUrlCloseClicked(eventId: String, url: String) {}
    override fun onPreviewUrlImageClicked(sharedView: View?, mxcUrl: String?, title: String?) {}
}
