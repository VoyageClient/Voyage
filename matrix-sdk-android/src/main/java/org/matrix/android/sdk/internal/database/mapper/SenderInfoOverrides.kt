/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.mapper

import org.matrix.android.sdk.api.session.profile.ColorPreference
import org.matrix.android.sdk.api.session.profile.ProfileOverrides
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.util.MatrixItem

internal fun overriddenSenderInfo(
        userId: String,
        displayName: String?,
        isUniqueDisplayName: Boolean,
        avatarUrl: String?,
        colorPreference: ColorPreference? = null,
        roomId: String? = null,
): SenderInfo {
    val hasOverrideName = ProfileOverrides.fieldsFor(userId, roomId)?.containsKey(ProfileOverrides.FIELD_DISPLAY_NAME) == true
    return SenderInfo(
            userId = userId,
            presentationRoomId = roomId,
            displayName = ProfileOverrides.displayNameOr(userId, displayName, roomId),
            // A user-chosen override needs no "(userId)" disambiguation suffix.
            isUniqueDisplayName = hasOverrideName || isUniqueDisplayName,
            avatarUrl = ProfileOverrides.avatarUrlOr(userId, avatarUrl, roomId),
            avatarDecryption = ProfileOverrides.avatarDecryptionFor(userId, roomId),
            colorPreference = colorPreference,
    )
}

internal fun overriddenUserItem(
        userId: String,
        displayName: String?,
        avatarUrl: String?,
        colorPreference: ColorPreference? = null,
) = MatrixItem.UserItem(
        userId,
        ProfileOverrides.displayNameOr(userId, displayName),
        ProfileOverrides.avatarUrlOr(userId, avatarUrl),
        ProfileOverrides.avatarDecryptionFor(userId),
        colorPreference = colorPreference,
)
