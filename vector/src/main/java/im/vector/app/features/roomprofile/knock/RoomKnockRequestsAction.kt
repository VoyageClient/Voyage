/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.knock

import im.vector.app.core.platform.VectorViewModelAction
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary

sealed class RoomKnockRequestsAction : VectorViewModelAction {
    data class Accept(val roomMemberSummary: RoomMemberSummary) : RoomKnockRequestsAction()
    data class Decline(val roomMemberSummary: RoomMemberSummary) : RoomKnockRequestsAction()
}
