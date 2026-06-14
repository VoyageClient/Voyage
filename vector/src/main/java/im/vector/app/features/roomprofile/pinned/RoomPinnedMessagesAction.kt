/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.pinned

import im.vector.app.core.platform.VectorViewModelAction

sealed interface RoomPinnedMessagesAction : VectorViewModelAction {
    data class Unpin(val eventId: String) : RoomPinnedMessagesAction
}
