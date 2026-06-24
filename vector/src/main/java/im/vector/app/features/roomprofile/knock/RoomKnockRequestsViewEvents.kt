/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.knock

import im.vector.app.core.platform.VectorViewEvents

sealed class RoomKnockRequestsViewEvents : VectorViewEvents {
    data class ToastMessage(val info: String) : RoomKnockRequestsViewEvents()
}
