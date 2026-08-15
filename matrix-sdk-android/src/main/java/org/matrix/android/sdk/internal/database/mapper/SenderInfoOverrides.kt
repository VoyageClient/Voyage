/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.mapper

import org.matrix.android.sdk.api.session.profile.ProfileOverrides
import org.matrix.android.sdk.api.session.room.sender.SenderInfo

internal fun overriddenSenderInfo(
        userId: String,
        displayName: String?,
        isUniqueDisplayName: Boolean,
        avatarUrl: String?,
): SenderInfo {
    val overrideName = ProfileOverrides.displayNameFor(userId)
    return SenderInfo(
            userId = userId,
            displayName = overrideName ?: displayName,
            // A user-chosen override needs no "(userId)" disambiguation suffix.
            isUniqueDisplayName = overrideName != null || isUniqueDisplayName,
            avatarUrl = ProfileOverrides.avatarUrlFor(userId) ?: avatarUrl,
    )
}
