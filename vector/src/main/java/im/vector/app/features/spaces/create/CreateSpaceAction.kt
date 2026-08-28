/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.spaces.create

import android.net.Uri
import im.vector.app.core.platform.VectorViewModelAction
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules

sealed class CreateSpaceAction : VectorViewModelAction {
    data class NameChanged(val name: String) : CreateSpaceAction()
    data class TopicChanged(val topic: String) : CreateSpaceAction()
    data class SpaceAliasChanged(val aliasLocalPart: String) : CreateSpaceAction()
    data class SetAvatar(val uri: Uri?) : CreateSpaceAction()
    object OnBackPressed : CreateSpaceAction()
    object NextFromDetails : CreateSpaceAction()
    object NextFromDefaultRooms : CreateSpaceAction()
    object NextFromAdd3pid : CreateSpaceAction()
    data class DefaultRoomNameChanged(val index: Int, val name: String) : CreateSpaceAction()
    data class DefaultInvite3pidChanged(val index: Int, val email: String) : CreateSpaceAction()
    data class SetJoinRule(val joinRule: RoomJoinRules) : CreateSpaceAction()
    data class SetIsEncrypted(val isEncrypted: Boolean) : CreateSpaceAction()
    object ToggleShowAdvanced : CreateSpaceAction()
    data class SetDisableFederation(val disableFederation: Boolean) : CreateSpaceAction()
    data class SetRoomVersion(val version: String) : CreateSpaceAction()
    data class SetMyPowerLevel(val powerLevel: Int?) : CreateSpaceAction()
    data class SetInitialStateJson(val json: String) : CreateSpaceAction()
}
