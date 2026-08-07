/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.membership.leaving

import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface ForgetRoomTask : Task<ForgetRoomTask.Params, Unit> {
    data class Params(
            val roomId: String,
    )
}

internal class DefaultForgetRoomTask @Inject constructor(
        private val roomAPI: RoomAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
) : ForgetRoomTask {

    override suspend fun execute(params: ForgetRoomTask.Params) {
        executeRequest(globalErrorReceiver) {
            roomAPI.forget(params.roomId)
        }
    }
}
