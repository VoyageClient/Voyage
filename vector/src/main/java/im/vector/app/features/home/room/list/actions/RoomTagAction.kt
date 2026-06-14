/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list.actions

import im.vector.app.core.platform.VectorViewModelAction

sealed interface RoomTagAction : VectorViewModelAction {
    data class AddTag(val tag: String) : RoomTagAction
    data class RemoveTag(val tag: String) : RoomTagAction
}
