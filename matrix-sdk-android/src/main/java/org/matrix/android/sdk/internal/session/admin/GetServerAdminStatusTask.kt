/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.admin

import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.session.admin.ServerAdminStatus
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.task.Task
import timber.log.Timber
import javax.inject.Inject

internal interface GetServerAdminStatusTask : Task<Unit, ServerAdminStatus>

internal class DefaultGetServerAdminStatusTask @Inject constructor(
        private val adminAPI: AdminAPI,
        @UserId private val userId: String,
        private val globalErrorReceiver: GlobalErrorReceiver,
) : GetServerAdminStatusTask {

    override suspend fun execute(params: Unit): ServerAdminStatus {
        return try {
            val result = executeRequest(globalErrorReceiver) {
                adminAPI.getAdminStatus(userId)
            }
            if (result.admin == true) ServerAdminStatus.YES else ServerAdminStatus.NO
        } catch (failure: Throwable) {
            // Catch everything: executeRequest converts an IOException into Failure.NetworkConnection
            // and anything unclassified into Failure.Unknown, so no narrower clause would match and
            // this would escape into a caller that has no handler.
            Timber.d(failure, "Could not determine server admin status")
            when {
                // Synapse rejects non-admins outright, which is itself a definitive "no". Any other
                // status (404 from a non-Synapse server, or an unrouted /_synapse) tells us nothing.
                (failure as? Failure.ServerError)?.httpCode == 403 -> ServerAdminStatus.NO
                (failure as? Failure.OtherServerError)?.httpCode == 403 -> ServerAdminStatus.NO
                else -> ServerAdminStatus.UNKNOWN
            }
        }
    }
}
