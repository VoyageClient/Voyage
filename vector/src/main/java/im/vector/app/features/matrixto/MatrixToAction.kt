/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.matrixto

import im.vector.app.core.platform.VectorViewModelAction

sealed class MatrixToAction : VectorViewModelAction {
    object FailedToResolveRoom : MatrixToAction()
    data class JoinSpace(val spaceID: String, val viaServers: List<String>?) : MatrixToAction()
    data class JoinRoom(val roomIdOrAlias: String, val viaServers: List<String>?) : MatrixToAction()
    data class KnockRoom(val roomIdOrAlias: String, val viaServers: List<String>?, val reason: String?) : MatrixToAction()
    data class OpenSpace(val spaceID: String) : MatrixToAction()
    data class OpenRoom(val roomId: String) : MatrixToAction()
}
