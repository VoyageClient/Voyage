/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.spaces.create

import im.vector.app.core.platform.VectorViewEvents

sealed class CreateSpaceEvents : VectorViewEvents {
    object NavigateToDetails : CreateSpaceEvents()
    object NavigateToAddRooms : CreateSpaceEvents()
    object NavigateToAdd3Pid : CreateSpaceEvents()
    object Dismiss : CreateSpaceEvents()
    data class FinishSuccess(val spaceId: String, val defaultRoomId: String?, val isJustMe: Boolean) : CreateSpaceEvents()
    data class ShowModalError(val errorMessage: String) : CreateSpaceEvents()
    object HideModalLoading : CreateSpaceEvents()
    data class ShowModalLoading(val message: String?) : CreateSpaceEvents()
}
