/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roommemberprofile

import android.net.Uri
import im.vector.app.core.platform.VectorViewModelAction
import org.matrix.android.sdk.api.session.profile.ColorPreference
import org.matrix.android.sdk.api.session.room.powerlevels.UserPowerLevel

sealed class RoomMemberProfileAction : VectorViewModelAction {
    object RetryFetchingInfo : RoomMemberProfileAction()
    object IgnoreUser : RoomMemberProfileAction()
    data class BanOrUnbanUser(val reason: String?) : RoomMemberProfileAction()
    data class KickUser(val reason: String?) : RoomMemberProfileAction()
    object RedactAllMessages : RoomMemberProfileAction()
    object InviteUser : RoomMemberProfileAction()
    object VerifyUser : RoomMemberProfileAction()
    data class SetPowerLevel(val previousValue: UserPowerLevel, val newValue: UserPowerLevel.Value, val askForValidation: Boolean) : RoomMemberProfileAction()
    data class SetProfileOverrideColor(val color: ColorPreference?) : RoomMemberProfileAction()
    data class SetProfileColorSameForThemes(val same: Boolean) : RoomMemberProfileAction()
    data class SetProfileOverrideDisplayName(val displayName: String?) : RoomMemberProfileAction()
    data class SetProfileOverrideAvatar(val avatarUri: Uri?) : RoomMemberProfileAction()
    object ResetProfileOverrides : RoomMemberProfileAction()
    data class OpenOrCreateDm(val userId: String) : RoomMemberProfileAction()

    /** MSC4441: set (or clear, when null/blank) the personal note on this user. */
    data class SetPersonalNote(val note: String?) : RoomMemberProfileAction()

    /** A note save was aborted (e.g. the recovery-key flow was cancelled): re-sync the editor with the stored note. */
    object RevertPersonalNote : RoomMemberProfileAction()

    /**
     * The 4S flow handed back its result cipher: cache the ADK from it, then save
     * [pendingNote] if a note edit was waiting on the key (null = just re-read the note).
     */
    data class GotAdkFromSsss(val cipher: String, val alias: String, val pendingNote: String? = null) : RoomMemberProfileAction()

    /** The recovery-key flow for an encrypted profile-overrides update was cancelled: drop the pending change. */
    object AbortPendingOverrideUpdate : RoomMemberProfileAction()
}
