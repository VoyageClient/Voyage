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
import org.matrix.android.sdk.api.session.room.model.RoomSummary
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
    val hideByMode = isMediaHiddenInRoom(session.getRoomSummary(roomId), vectorPreferences)
    return hideByMode && !mediaContentRevealManager.isRevealed(event.eventId)
}

/**
 * Whether media is hidden in [roomId] per the media-preview setting, regardless of any specific event.
 */
fun isMediaHiddenInRoom(
        roomId: String?,
        session: Session,
        vectorPreferences: VectorPreferences,
): Boolean {
    roomId ?: return false
    return isMediaHiddenInRoom(session.getRoomSummary(roomId), vectorPreferences)
}

fun isMediaHiddenInRoom(
        summary: RoomSummary?,
        vectorPreferences: VectorPreferences,
): Boolean {
    return when (vectorPreferences.getMediaPreviewMode(summary?.roomId)) {
        MediaPreviewMode.ALWAYS_SHOW -> false
        MediaPreviewMode.ALWAYS_HIDE -> true
        MediaPreviewMode.PRIVATE -> summary?.joinRules !in PRIVATE_JOIN_RULES
        MediaPreviewMode.DIRECT -> summary?.isDirect != true
    }
}

/**
 * Whether avatars should be forced to the default placeholder in [roomId]: the "hide avatars" toggle is on
 * and media is hidden there. Drives the timeline, member list and the member profile opened from the room.
 */
fun shouldHideAvatars(
        roomId: String?,
        session: Session,
        vectorPreferences: VectorPreferences,
): Boolean {
    return vectorPreferences.hideAvatarsInHiddenMediaRooms() && isMediaHiddenInRoom(roomId, session, vectorPreferences)
}

fun shouldHideAvatars(
        summary: RoomSummary?,
        vectorPreferences: VectorPreferences,
): Boolean {
    return vectorPreferences.hideAvatarsInHiddenMediaRooms() && isMediaHiddenInRoom(summary, vectorPreferences)
}
