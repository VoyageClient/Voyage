/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list.actions

import im.vector.app.core.platform.VectorViewModelAction

sealed interface RoomSectionAction : VectorViewModelAction {
    /** Move the room into [tag], or out of its current section when null. */
    data class MoveToSection(val tag: String?) : RoomSectionAction
    data class CreateSectionAndMove(val name: String) : RoomSectionAction
    data class RenameSection(val tag: String, val newName: String) : RoomSectionAction
    data class RequestDeleteSection(val tag: String) : RoomSectionAction
    data class DeleteSection(val tag: String) : RoomSectionAction
}
