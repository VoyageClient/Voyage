/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.action

import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import javax.inject.Inject

class CheckIfCanReplyEventUseCase @Inject constructor() {

    fun execute(@Suppress("UNUSED_PARAMETER") event: TimelineEvent, messageContent: MessageContent?, actionPermissions: ActionPermissions): Boolean {
        if (!actionPermissions.canSendMessage) return false

        // Non-message events (membership changes, reactions, state events, etc.) carry no
        // MessageContent. They can all be replied to — the reply only references the target
        // event id, and the preview is rendered from the cached event by the receiving client.
        if (messageContent == null) return true

        // For actual message events keep filtering by msgType so we only reply to content we
        // know how to preview.
        return when (messageContent.msgType) {
            MessageType.MSGTYPE_TEXT,
            MessageType.MSGTYPE_NOTICE,
            MessageType.MSGTYPE_EMOTE,
            MessageType.MSGTYPE_IMAGE,
            MessageType.MSGTYPE_VIDEO,
            MessageType.MSGTYPE_AUDIO,
            MessageType.MSGTYPE_FILE,
            MessageType.MSGTYPE_GALLERY,
            MessageType.MSGTYPE_GALLERY_STABLE,
            MessageType.MSGTYPE_POLL_START,
            MessageType.MSGTYPE_POLL_END,
            MessageType.MSGTYPE_BEACON_INFO,
            MessageType.MSGTYPE_LOCATION -> true
            else -> false
        }
    }
}
