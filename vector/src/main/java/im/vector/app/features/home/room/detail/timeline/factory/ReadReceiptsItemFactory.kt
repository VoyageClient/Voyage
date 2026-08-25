/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.factory

import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.item.ReadReceiptData
import im.vector.app.features.home.room.detail.timeline.item.ReadReceiptsItem
import im.vector.app.features.home.room.detail.timeline.item.ReadReceiptsItem_
import im.vector.app.features.media.shouldHideAvatars
import im.vector.app.features.settings.VectorPreferences
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.model.ReadReceipt
import javax.inject.Inject

class ReadReceiptsItemFactory @Inject constructor(
        private val avatarRenderer: AvatarRenderer,
        private val session: Session,
        private val vectorPreferences: VectorPreferences,
) {

    fun create(
            eventId: String,
            roomId: String,
            readReceipts: List<ReadReceipt>,
            callback: TimelineEventController.Callback?,
            isFromThreadTimeLine: Boolean,
    ): ReadReceiptsItem? {
        if (readReceipts.isEmpty()) {
            return null
        }
        val hideAvatars = shouldHideAvatars(roomId, session, vectorPreferences)
        val readReceiptsData = readReceipts
                .map {
                    val avatarUrl = it.roomMember.avatarUrl.takeUnless { hideAvatars }
                    ReadReceiptData(
                            it.roomMember.userId,
                            avatarUrl,
                            it.roomMember.displayName,
                            it.originServerTs,
                            colorOnLight = it.roomMember.colorPreference?.onLight,
                            colorOnDark = it.roomMember.colorPreference?.onDark,
                    )
                }
                .sortedByDescending { it.timestamp }
        val threadReadReceiptsSupported = session.homeServerCapabilitiesService().getHomeServerCapabilities().canUseThreadReadReceiptsAndNotifications
        return ReadReceiptsItem_()
                .id("read_receipts_$eventId")
                .eventId(eventId)
                .readReceipts(readReceiptsData)
                .avatarRenderer(avatarRenderer)
                .shouldHideReadReceipts(isFromThreadTimeLine && !threadReadReceiptsSupported)
                .clickListener {
                    callback?.onReadReceiptsClicked(readReceiptsData)
                }
    }
}
