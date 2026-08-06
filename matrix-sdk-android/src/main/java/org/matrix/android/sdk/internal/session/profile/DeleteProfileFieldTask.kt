/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.profile

import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.network.shouldFallBackToUnstableEndpoint
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal abstract class DeleteProfileFieldTask : Task<DeleteProfileFieldTask.Params, Unit> {
    data class Params(
            val userId: String,
            val keyName: String
    )
}

internal class DefaultDeleteProfileFieldTask @Inject constructor(
        private val profileAPI: ProfileAPI,
        private val globalErrorReceiver: GlobalErrorReceiver
) : DeleteProfileFieldTask() {

    override suspend fun execute(params: Params) {
        try {
            executeRequest(globalErrorReceiver) {
                profileAPI.deleteProfileField(params.userId, params.keyName)
            }
        } catch (failure: Throwable) {
            if (failure.shouldFallBackToUnstableEndpoint()) {
                executeRequest(globalErrorReceiver) {
                    profileAPI.deleteProfileFieldUnstable(params.userId, params.keyName)
                }
            } else {
                throw failure
            }
        }
    }
}
