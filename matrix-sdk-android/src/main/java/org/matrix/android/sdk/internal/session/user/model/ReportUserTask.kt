/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.user.model

import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.user.ReportUserBody
import org.matrix.android.sdk.internal.session.user.SearchUserAPI
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface ReportUserTask : Task<ReportUserTask.Params, Unit> {

    data class Params(
            val userId: String,
            val reason: String,
    )
}

internal class DefaultReportUserTask @Inject constructor(
        private val searchUserAPI: SearchUserAPI,
        private val globalErrorReceiver: GlobalErrorReceiver
) : ReportUserTask {

    override suspend fun execute(params: ReportUserTask.Params) {
        return executeRequest(globalErrorReceiver) {
            searchUserAPI.reportUser(params.userId, ReportUserBody(params.reason))
        }
    }
}
