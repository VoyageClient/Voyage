/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import im.vector.app.features.settings.VectorPreferences
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.profile.ProfileOverrides
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import javax.inject.Inject

/**
 * The sender name and avatar to show for a stored event, off the timeline: the profile as it was when
 * the message was sent, unless "show latest profile" is on. Mirrors [MessageInformationDataFactory],
 * which does the same for the timeline itself.
 *
 * Hits the member table, so call it off the main thread.
 */
class SenderProfileResolver @Inject constructor(
        private val session: Session,
        private val vectorPreferences: VectorPreferences,
) {

    /** One resolver per list build: a long list repeats the same few senders, and each miss is a DB read. */
    fun newSession() = Batch()

    fun resolve(roomId: String, senderInfo: SenderInfo, isRedacted: Boolean = false): SenderInfo =
            newSession().resolve(roomId, senderInfo, isRedacted)

    inner class Batch {

        private val members = HashMap<Pair<String, String>, RoomMemberSummary?>()

        fun resolve(roomId: String, senderInfo: SenderInfo, isRedacted: Boolean = false): SenderInfo =
                resolveWith(senderInfo, isRedacted) { userId ->
                    members.getOrPut(roomId to userId) {
                        session.getRoom(roomId)?.membershipService()?.getRoomMember(userId)
                                // The room's members may never have been loaded (lazy loading), or the
                                // sender may have left: the account-wide user table still knows them,
                                // and a name is better than the bare id.
                                ?: session.userService().getUser(userId)?.let {
                                    RoomMemberSummary(
                                            userId = it.userId,
                                            displayName = it.displayName,
                                            avatarUrl = it.avatarUrl,
                                            membership = Membership.JOIN,
                                    )
                                }
                    }
                }
    }

    private inline fun resolveWith(
            senderInfo: SenderInfo,
            isRedacted: Boolean,
            liveMemberOf: (String) -> RoomMemberSummary?,
    ): SenderInfo {
        val useLive = vectorPreferences.showLiveSenderInfo()
        val storedName = senderInfo.displayName
        val storedAvatar = senderInfo.avatarUrl
        // Sender name/avatar are denormalized at sync time: null = member state unknown when stored, so
        // fall back to the current member; "" = known but genuinely empty then, no fallback. A redaction
        // strips the membership event, where null means gone rather than unknown.
        val liveMember = if (useLive || senderInfo.colorPreference == null || ((storedName == null || storedAvatar == null) && !isRedacted)) {
            liveMemberOf(senderInfo.userId)
        } else {
            null
        }
        val name = when {
            useLive && liveMember != null -> liveMember.displayName?.takeUnless { it.isBlank() } ?: senderInfo.userId
            storedName == null -> liveMember?.displayName?.takeUnless { it.isBlank() } ?: senderInfo.disambiguatedDisplayName
            else -> senderInfo.disambiguatedDisplayName
        }
        val avatar = when {
            useLive && liveMember != null -> liveMember.avatarUrl
            storedAvatar == null -> liveMember?.avatarUrl
            else -> storedAvatar.takeUnless { it.isEmpty() }
        }
        // The name is already disambiguated, so it must not be decorated a second time. Carrying the
        // member's colour matters as much as the name: a null one makes every avatar render ask the
        // server for that user's profile, which a list of a few hundred senders turns into a flood.
        return senderInfo.copy(
                // A per-user override outranks every source, as it does everywhere else it is applied.
                displayName = ProfileOverrides.displayNameOr(senderInfo.userId, name),
                isUniqueDisplayName = true,
                avatarUrl = ProfileOverrides.avatarUrlOr(senderInfo.userId, avatar),
                avatarDecryption = ProfileOverrides.avatarDecryptionFor(senderInfo.userId),
                colorPreference = senderInfo.colorPreference ?: liveMember?.colorPreference,
        )
    }
}
