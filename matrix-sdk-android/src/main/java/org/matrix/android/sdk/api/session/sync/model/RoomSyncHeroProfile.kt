/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.sync.model

/** A hero of a room, as MSC4186 describes it: the identity a room without a name is named after. */
data class RoomSyncHeroProfile(
        val userId: String,
        val displayName: String?,
        val avatarUrl: String?,
)
