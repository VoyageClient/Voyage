/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomdirectory.pendingrequests

import im.vector.app.core.platform.VectorViewModelAction

sealed class PendingJoinRequestsViewAction : VectorViewModelAction {
    data class CancelRequest(val roomId: String) : PendingJoinRequestsViewAction()
}
