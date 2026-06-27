/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.auth.db

import com.squareup.moshi.Moshi
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig
import org.matrix.android.sdk.internal.auth.login.ResetPasswordData
import org.matrix.android.sdk.internal.auth.registration.ThreePidData
import javax.inject.Inject

internal class PendingSessionMapper @Inject constructor(moshi: Moshi) {

    private val homeServerConnectionConfigAdapter = moshi.adapter(HomeServerConnectionConfig::class.java)
    private val resetPasswordDataAdapter = moshi.adapter(ResetPasswordData::class.java)
    private val threePidDataAdapter = moshi.adapter(ThreePidData::class.java)

    fun map(
            homeServerConnectionConfigJson: String,
            clientSecret: String,
            sendAttempt: Int,
            resetPasswordDataJson: String?,
            currentSession: String?,
            isRegistrationStarted: Boolean,
            currentThreePidDataJson: String?,
    ): PendingSessionData? {
        val homeServerConnectionConfig = homeServerConnectionConfigAdapter.fromJson(homeServerConnectionConfigJson) ?: return null
        val resetPasswordData = resetPasswordDataJson?.let { resetPasswordDataAdapter.fromJson(it) }
        val threePidData = currentThreePidDataJson?.let { threePidDataAdapter.fromJson(it) }
        return PendingSessionData(
                homeServerConnectionConfig = homeServerConnectionConfig,
                clientSecret = clientSecret,
                sendAttempt = sendAttempt,
                resetPasswordData = resetPasswordData,
                currentSession = currentSession,
                isRegistrationStarted = isRegistrationStarted,
                currentThreePidData = threePidData,
        )
    }

    fun toColumns(sessionData: PendingSessionData): Columns {
        return Columns(
                homeServerConnectionConfigJson = homeServerConnectionConfigAdapter.toJson(sessionData.homeServerConnectionConfig),
                clientSecret = sessionData.clientSecret,
                sendAttempt = sessionData.sendAttempt,
                resetPasswordDataJson = resetPasswordDataAdapter.toJson(sessionData.resetPasswordData),
                currentSession = sessionData.currentSession,
                isRegistrationStarted = sessionData.isRegistrationStarted,
                currentThreePidDataJson = threePidDataAdapter.toJson(sessionData.currentThreePidData),
        )
    }

    data class Columns(
            val homeServerConnectionConfigJson: String,
            val clientSecret: String,
            val sendAttempt: Int,
            val resetPasswordDataJson: String?,
            val currentSession: String?,
            val isRegistrationStarted: Boolean,
            val currentThreePidDataJson: String?,
    )
}
