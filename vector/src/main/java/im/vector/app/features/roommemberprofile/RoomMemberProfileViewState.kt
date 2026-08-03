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
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.powerlevels.RoomPowerLevels
import org.matrix.android.sdk.api.util.MatrixItem

data class RoomMemberProfileViewState(
        val userId: String,
        val roomId: String?,
        val isSpace: Boolean = false,
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
        val userColorOverride: String? = null,
        // "she/her • PST" line from MSC4247 pronouns + MSC4175 time zone, or null when neither is set
        val profileFieldsLine: String? = null,
        // Raw per-room override of the MSC4427 profile banner (member-event field):
        // null = absent, "" = explicitly blanked in this room
        val memberBannerUrl: String? = null,
        // MSC4427 profile banner field
        val globalBannerUrl: String? = null,
        val actionPermissions: ActionPermissions = ActionPermissions()
) : MavericksState {

    constructor(args: RoomMemberProfileArgs) : this(userId = args.userId, roomId = args.roomId)

    fun resolvedBannerUrl(): String? = when {
        memberBannerUrl != null -> memberBannerUrl.takeIf { it.isNotEmpty() }
        else -> globalBannerUrl?.takeIf { it.isNotEmpty() }
    }
}

data class ActionPermissions(
        val canKick: Boolean = false,
        val canBan: Boolean = false,
        val canInvite: Boolean = false,
        val canEditPowerLevel: Boolean = false,
        val canRedact: Boolean = false
)
