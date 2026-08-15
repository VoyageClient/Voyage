/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import org.matrix.android.sdk.api.session.profile.ProfileOverrides
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary

/**
 * The name to write into a message body when mentioning this member. A local display-name override
 * means nothing to anyone else in the room, so the body carries the name the user publishes; the
 * pill drawn over it still shows the override.
 */
fun RoomMemberSummary.bodyName(): String? {
    ProfileOverrides.displayNameFor(userId) ?: return displayName
    return originalDisplayName?.takeIf { it.isNotBlank() } ?: userId
}
