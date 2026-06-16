/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import im.vector.app.features.settings.MediaPreviewMode
import im.vector.app.features.settings.VectorPreferences
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent

private val PRIVATE_JOIN_RULES = setOf(
        RoomJoinRules.INVITE,
        RoomJoinRules.KNOCK,
        RoomJoinRules.RESTRICTED,
        RoomJoinRules.PRIVATE,
)

/**
 * Whether a media thumbnail/preview for [event] should be hidden (shown as blurhash/solid) per the
 * room's media-preview setting, mirroring the main timeline. Honours a prior in-timeline reveal and
 * never hides the user's own media. Shared by the timeline, reply previews, the composer and the
 * message long-press preview.
 */
fun shouldHideMediaPreview(
        event: TimelineEvent,
        session: Session,
        vectorPreferences: VectorPreferences,
        mediaContentRevealManager: MediaContentRevealManager,
): Boolean {
    if (event.senderInfo.userId == session.myUserId) return false
    val roomId = event.root.roomId ?: return false
    val summary = session.getRoomSummary(roomId)
    val hideByMode = when (vectorPreferences.getMediaPreviewMode()) {
        MediaPreviewMode.ALWAYS_SHOW -> false
        MediaPreviewMode.ALWAYS_HIDE -> true
        MediaPreviewMode.PRIVATE -> summary?.joinRules !in PRIVATE_JOIN_RULES
        MediaPreviewMode.DIRECT -> summary?.isDirect != true
    }
    return hideByMode && !mediaContentRevealManager.isRevealed(event.eventId)
}
