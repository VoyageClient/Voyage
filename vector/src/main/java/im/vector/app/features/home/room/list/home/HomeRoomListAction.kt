/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list.home

import im.vector.app.core.platform.VectorViewModelAction
import im.vector.app.features.home.room.list.home.header.HomeRoomFilterTab
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.notification.RoomNotificationState

sealed class HomeRoomListAction : VectorViewModelAction {
    data class SelectRoom(val roomSummary: RoomSummary) : HomeRoomListAction()
    data class ChangeRoomNotificationState(val roomId: String, val notificationState: RoomNotificationState) : HomeRoomListAction()
    data class ToggleTag(val roomId: String, val tag: String) : HomeRoomListAction()
    data class SetMarkedUnread(val roomId: String, val markedUnread: Boolean) : HomeRoomListAction()
    data class MarkRoomAsRead(val roomId: String) : HomeRoomListAction()
    data class LeaveRoom(val roomId: String) : HomeRoomListAction()
    data class ForgetRoom(val roomId: String) : HomeRoomListAction()
    data class ChangeRoomFilter(val filter: HomeRoomFilterTab) : HomeRoomListAction()
    data class CreateSection(val name: String) : HomeRoomListAction()
    data class RenameSection(val tag: String, val newName: String) : HomeRoomListAction()
    data class MoveSection(val tag: String, val up: Boolean) : HomeRoomListAction()
    data class RequestDeleteSection(val tag: String) : HomeRoomListAction()
    data class DeleteSection(val tag: String) : HomeRoomListAction()
    object DeleteAllLocalRoom : HomeRoomListAction()
}
