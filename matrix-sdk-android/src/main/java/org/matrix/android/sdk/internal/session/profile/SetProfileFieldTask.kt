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

internal abstract class SetProfileFieldTask : Task<SetProfileFieldTask.Params, Unit> {
    data class Params(
            val userId: String,
            val keyName: String,
            val value: Any
    )
}

internal class DefaultSetProfileFieldTask @Inject constructor(
        private val profileAPI: ProfileAPI,
        private val globalErrorReceiver: GlobalErrorReceiver
) : SetProfileFieldTask() {

    override suspend fun execute(params: Params) {
        val body = mapOf(params.keyName to params.value)
        try {
            executeRequest(globalErrorReceiver) {
                profileAPI.setProfileField(params.userId, params.keyName, body)
            }
        } catch (failure: Throwable) {
            if (failure.shouldFallBackToUnstableEndpoint()) {
                executeRequest(globalErrorReceiver) {
                    profileAPI.setProfileFieldUnstable(params.userId, params.keyName, body)
                }
            } else {
                throw failure
            }
        }
    }
}
