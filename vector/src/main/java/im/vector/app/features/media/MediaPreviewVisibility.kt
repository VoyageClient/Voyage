/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import im.vector.app.features.home.room.detail.timeline.helper.timelineStableId
import im.vector.app.features.settings.MediaPreviewMode
import im.vector.app.features.settings.VectorPreferences
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getLastEditNewContent
import org.matrix.android.sdk.api.util.MatrixItem

const val MSC4193_SPOILER_KEY = "page.codeberg.everypizza.msc4193.spoiler"
private const val MSC4193_STABLE_SPOILER_KEY = "m.spoiler"

fun TimelineEvent.isMediaSpoiler(): Boolean {
    val content = getLastEditNewContent() ?: root.getClearContent()
    return content?.get(MSC4193_SPOILER_KEY) == true || content?.get(MSC4193_STABLE_SPOILER_KEY) == true
}

private val PRIVATE_JOIN_RULES = setOf(
        RoomJoinRules.INVITE,
        RoomJoinRules.KNOCK,
        RoomJoinRules.RESTRICTED,
        RoomJoinRules.KNOCK_RESTRICTED,
        RoomJoinRules.PRIVATE,
)

/**
 * Whether a media thumbnail/preview for [event] should be hidden (shown as blurhash/solid) per the
 * room's media-preview setting, mirroring the main timeline. Honours a prior in-timeline reveal and
 * never hides the user's own non-spoiler media. Shared by the timeline, reply previews, the composer and the
 * message long-press preview.
 */
fun shouldHideMediaPreview(
        event: TimelineEvent,
        session: Session,
        vectorPreferences: VectorPreferences,
        mediaContentRevealManager: MediaContentRevealManager,
): Boolean {
    if (event.isMediaSpoiler()) return !mediaContentRevealManager.isRevealed(event.timelineStableId())
    if (event.senderInfo.userId == session.myUserId) return false
    val roomId = event.root.roomId ?: return false
    val hideByMode = isMediaHiddenInRoom(session.getRoomSummary(roomId), vectorPreferences)
    return hideByMode && !mediaContentRevealManager.isRevealed(event.timelineStableId())
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
 * and media is hidden there. Covers the room's own avatar and every other user's avatar shown in it.
 * Invites are left to the separate "hide avatars on invites" toggle.
 */
fun shouldHideAvatars(
        roomId: String?,
        session: Session,
        vectorPreferences: VectorPreferences,
): Boolean {
    if (!vectorPreferences.hideAvatarsInHiddenMediaRooms()) return false
    roomId ?: return false
    if (vectorPreferences.getMediaPreviewMode(roomId) == MediaPreviewMode.ALWAYS_SHOW) return false
    return hidesAvatars(session.getRoomSummary(roomId), vectorPreferences)
}

fun shouldHideAvatars(
        summary: RoomSummary?,
        vectorPreferences: VectorPreferences,
): Boolean {
    return vectorPreferences.hideAvatarsInHiddenMediaRooms() && hidesAvatars(summary, vectorPreferences)
}

private fun hidesAvatars(summary: RoomSummary?, vectorPreferences: VectorPreferences): Boolean {
    return summary?.membership != Membership.INVITE && isMediaHiddenInRoom(summary, vectorPreferences)
}

/**
 * [matrixItem] without its avatar when [shouldHideAvatars] applies to it: a room, space or @room item in its own
 * room, or a user other than us shown in [roomId].
 */
fun MatrixItem.withAvatarHiddenIn(
        roomId: String?,
        session: Session,
        vectorPreferences: VectorPreferences,
): MatrixItem {
    if (avatarUrl.isNullOrEmpty() && avatarDecryption == null) return this
    if (!vectorPreferences.hideAvatarsInHiddenMediaRooms()) return this
    val hidden = when (this) {
        is MatrixItem.RoomItem, is MatrixItem.SpaceItem, is MatrixItem.EveryoneInRoomItem -> shouldHideAvatars(id, session, vectorPreferences)
        is MatrixItem.RoomAliasItem -> shouldHideAvatars(session.getRoomSummary(id), vectorPreferences)
        is MatrixItem.UserItem -> id != session.myUserId && shouldHideAvatars(roomId, session, vectorPreferences)
        else -> false
    }
    return if (hidden) updateAvatar(null) else this
}
