/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roommemberprofile

import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MavericksState
import com.airbnb.mvrx.Uninitialized
import org.matrix.android.sdk.api.session.crypto.crosssigning.MXCrossSigningInfo
import org.matrix.android.sdk.api.session.profile.ColorPreference
import org.matrix.android.sdk.api.session.profile.UserBio
import org.matrix.android.sdk.api.session.profile.UserStatus
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.powerlevels.RoomPowerLevels
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.api.util.MatrixItem

data class RoomMemberProfileViewState(
        val userId: String,
        val roomId: String?,
        val isSpace: Boolean = false,
        val isHistoricalOrWatchedRoom: Boolean = false,
        val showAsMember: Boolean = false,
        val isMine: Boolean = false,
        val isIgnored: Async<Boolean> = Uninitialized,
        val isRoomEncrypted: Boolean = false,
        val isAlgorithmSupported: Boolean = true,
        val roomPowerLevels: RoomPowerLevels? = null,
        val userPowerLevelString: Async<String> = Uninitialized,
        val userMatrixItem: Async<MatrixItem> = Uninitialized,
        val userMXCrossSigningInfo: MXCrossSigningInfo? = null,
        val allDevicesAreTrusted: Boolean = false,
        val allDevicesAreCrossSignedTrusted: Boolean = false,
        val asyncMembership: Async<Membership> = Uninitialized,
        val hasReadReceipt: Boolean = false,
        // im.vector.setting.override_colors: the pre-MSC4522 per-user color spec, a fallback for overrides set by older clients
        val userColorOverride: String? = null,
        // im.voyage.setting.profile_overrides fields for this user
        val profileOverrideDisplayName: String? = null,
        val profileOverrideAvatarUrl: String? = null,
        val profileOverrideColor: ColorPreference? = null,
        val hasProfileOverrides: Boolean = false,
        val profileColorSameForThemes: Boolean = true,
        // Bumped when resolved name colors change, so the header and Personalization rows re-render.
        val colorGeneration: Long = 0,
        // "she/her • PST" line from MSC4247 pronouns + MSC4175 time zone, or null when neither is set
        val profileFieldsLine: String? = null,
        // MSC4427 profile banner field
        val globalBannerUrl: String? = null,
        // MSC4426 status / MSC4440 biography
        val status: UserStatus? = null,
        val bio: UserBio? = null,
        // Raw MSC4133 profile dict, for the developer-mode source viewer
        val profileJson: JsonDict? = null,
        val actionPermissions: ActionPermissions = ActionPermissions()
) : MavericksState {

    constructor(args: RoomMemberProfileArgs) : this(userId = args.userId, roomId = args.roomId)

    fun resolvedBannerUrl(): String? = globalBannerUrl?.takeIf { it.isNotEmpty() }
}

data class ActionPermissions(
        val canKick: Boolean = false,
        val canBan: Boolean = false,
        val canInvite: Boolean = false,
        val canEditPowerLevel: Boolean = false,
        val canRedact: Boolean = false
)
