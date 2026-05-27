/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.permissions

import im.vector.app.core.resources.StringProvider
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.room.powerlevels.Role
import org.matrix.android.sdk.api.session.room.powerlevels.UserPowerLevel
import javax.inject.Inject

class RoleFormatter @Inject constructor(
        private val stringProvider: StringProvider
) {
    fun format(role: Role): String {
        return when (role) {
            Role.Admin -> stringProvider.getString(CommonStrings.power_level_admin)
            Role.Moderator -> stringProvider.getString(CommonStrings.power_level_moderator)
            Role.User -> stringProvider.getString(CommonStrings.power_level_default)
            Role.Creator -> stringProvider.getString(CommonStrings.power_level_owner)
            Role.SuperAdmin -> stringProvider.getString(CommonStrings.power_level_owner)
        }
    }

    /**
     * Format a [UserPowerLevel] preserving the numeric value when it does not match a
     * preset (so a user with PL=45 is shown as "Custom (45)" rather than collapsing to "Default").
     */
    fun format(powerLevel: UserPowerLevel): String {
        if (powerLevel is UserPowerLevel.Value) {
            when (powerLevel.value) {
                UserPowerLevel.User.value -> return stringProvider.getString(CommonStrings.power_level_default)
                UserPowerLevel.Moderator.value -> return stringProvider.getString(CommonStrings.power_level_moderator)
                UserPowerLevel.Admin.value -> return stringProvider.getString(CommonStrings.power_level_admin)
                UserPowerLevel.SuperAdmin.value -> return stringProvider.getString(CommonStrings.power_level_owner)
                else -> return stringProvider.getString(CommonStrings.power_level_custom, powerLevel.value)
            }
        }
        // Infinite => Creator/Owner
        return stringProvider.getString(CommonStrings.power_level_owner)
    }
}
