/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.spaces.create

import android.net.Uri
import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MavericksState
import com.airbnb.mvrx.Uninitialized
import im.vector.app.features.roomdirectory.createroom.AdvancedRoomOptions
import im.vector.app.features.roomdirectory.createroom.CreatableRoomVersion
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules

data class CreateSpaceState(
        val name: String? = null,
        val avatarUri: Uri? = null,
        val topic: String = "",
        val step: Step = Step.SetDetails,
        val joinRule: RoomJoinRules = RoomJoinRules.INVITE,
        val supportsKnock: Boolean = false,
        val isEncrypted: Boolean = false,
        val homeServerName: String = "",
        val aliasLocalPart: String? = null,
        val aliasManuallyModified: Boolean = false,
        val aliasVerificationTask: Async<Boolean> = Uninitialized,
        val nameInlineError: String? = null,
        val defaultRooms: Map<Int, String?>? = null, // Int: position in form
        val default3pidInvite: Map<Int, String?>? = null, // Int: position in form
        val emailValidationResult: Map<Int, Boolean>? = null, // Int: position in form
        val creationResult: Async<String> = Uninitialized,
        val canInviteByMail: Boolean = false,
        override val showAdvanced: Boolean = false,
        override val disableFederation: Boolean = false,
        override val roomVersion: String? = null,
        override val defaultRoomVersion: String? = null,
        override val availableRoomVersions: List<CreatableRoomVersion> = emptyList(),
        override val myPowerLevelOverride: Int? = null,
        override val isDeveloperMode: Boolean = false,
        override val initialStateJson: String = "",
        override val initialStateJsonInvalid: Boolean = false,
) : MavericksState, AdvancedRoomOptions {

    val isPublic: Boolean get() = joinRule == RoomJoinRules.PUBLIC

    /** Inviting people by email only makes sense for a space they cannot simply walk into. */
    val showsInviteStep: Boolean get() = !isPublic && canInviteByMail

    /** Nobody was invited and no room was asked for, so there is nothing in the space yet. */
    fun isJustMe(): Boolean {
        return default3pidInvite.orEmpty().values.none { it?.isNotBlank() == true } &&
                defaultRooms.orEmpty().values.none { it?.isNotBlank() == true }
    }

    enum class Step {
        SetDetails,
        AddEmailsOrInvites,
        AddRooms
    }
}
