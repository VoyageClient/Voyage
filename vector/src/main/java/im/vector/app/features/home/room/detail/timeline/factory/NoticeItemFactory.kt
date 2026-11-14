/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.factory

import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.format.NoticeEventFormatter
import im.vector.app.features.home.room.detail.timeline.helper.AvatarSizeProvider
import im.vector.app.features.home.room.detail.timeline.helper.MessageInformationDataFactory
import im.vector.app.features.home.room.detail.timeline.item.NoticeItem
import im.vector.app.features.home.room.detail.timeline.item.NoticeItem_
import im.vector.app.features.home.room.detail.timeline.tools.clampForMaxLines
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import javax.inject.Inject

class NoticeItemFactory @Inject constructor(
        private val eventFormatter: NoticeEventFormatter,
        private val avatarRenderer: AvatarRenderer,
        private val informationDataFactory: MessageInformationDataFactory,
        private val avatarSizeProvider: AvatarSizeProvider
) {

    fun create(params: TimelineItemFactoryParams): NoticeItem? {
        val event = params.event
        val formattedText = eventFormatter.format(event, isDm = params.partialState.roomSummary?.isDirect.orFalse()) ?: return null
        val maxLines = when {
            event.root.getClearType() == EventType.STATE_ROOM_TOPIC -> 1
            event.root.getClearType() == EventType.STATE_ROOM_MEMBER &&
                    event.root.getClearContent().toModel<RoomMemberContent>()?.safeReason != null -> MEMBERSHIP_REASON_MAX_LINES
            else -> Int.MAX_VALUE
        }
        val informationData = informationDataFactory.create(params)
        val attributes = NoticeItem.Attributes(
                avatarRenderer = avatarRenderer,
                informationData = informationData,
                noticeText = EpoxyCharSequence(formattedText.clampForMaxLines(maxLines).prepareForDisplay()),
                itemLongClickListener = { view ->
                    params.callback?.onEventLongClicked(informationData, null, view) ?: false
                },
                itemDoubleTapListener = { params.callback?.onEventDoubleTapped(informationData) },
                readReceiptsCallback = params.callback,
                avatarClickListener = { params.callback?.onAvatarClicked(informationData) },
                reactionPillCallback = params.callback,
                reactionsSummaryEvents = params.reactionsSummaryEvents,
        )
        return NoticeItem_()
                .maxLines(maxLines)
                .leftGuideline(avatarSizeProvider.nonMessageGuideline(informationData))
                .highlighted(params.isHighlighted)
                .attributes(attributes)
    }

    companion object {
        private const val MEMBERSHIP_REASON_MAX_LINES = 3
    }
}
