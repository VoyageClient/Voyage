/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.auth.login

import dagger.Lazy
import okhttp3.OkHttpClient
import org.matrix.android.sdk.api.auth.LoginType
import org.matrix.android.sdk.api.auth.data.Credentials
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.internal.auth.AuthAPI
import org.matrix.android.sdk.internal.auth.SessionCreator
import org.matrix.android.sdk.internal.di.Unauthenticated
import org.matrix.android.sdk.internal.network.RetrofitFactory
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.network.httpclient.addSocketFactory
import org.matrix.android.sdk.internal.network.ssl.UnrecognizedCertificateException
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface DirectTokenLoginTask : Task<DirectTokenLoginTask.Params, Session> {
    data class Params(
            val homeServerConnectionConfig: HomeServerConnectionConfig,
            val accessToken: String
    )
}

internal class DefaultDirectTokenLoginTask @Inject constructor(
        @Unauthenticated
        private val okHttpClient: Lazy<OkHttpClient>,
        private val retrofitFactory: RetrofitFactory,
        private val sessionCreator: SessionCreator
) : DirectTokenLoginTask {

    override suspend fun execute(params: DirectTokenLoginTask.Params): Session {
        val client = buildClient(params.homeServerConnectionConfig)
        val homeServerUrl = params.homeServerConnectionConfig.homeServerUriBase

        val authAPI = retrofitFactory.create(client, homeServerUrl)
                .create(AuthAPI::class.java)

        val whoAmI = try {
            executeRequest(null) {
                authAPI.whoAmI("Bearer ${params.accessToken}")
            }
        } catch (throwable: Throwable) {
            throw when (throwable) {
                is UnrecognizedCertificateException -> Failure.UnrecognizedCertificateFailure(
                        homeServerUrl,
                        throwable.fingerprint
                )
                else -> throwable
            }
        }

        // sessionId is md5(userId|deviceId), so a token with no device would collide with any
        // other deviceless session for the same user.
        val deviceId = whoAmI.deviceId
                ?: throw IllegalArgumentException("This access token is not bound to a device and cannot be used to sign in")

        val credentials = Credentials(
                userId = whoAmI.userId,
                accessToken = params.accessToken,
                refreshToken = null,
                homeServer = null,
                deviceId = deviceId,
                discoveryInformation = null
        )

        return sessionCreator.createSession(credentials, params.homeServerConnectionConfig, LoginType.TOKEN)
    }

    private fun buildClient(homeServerConnectionConfig: HomeServerConnectionConfig): OkHttpClient {
        return okHttpClient.get()
                .newBuilder()
                .addSocketFactory(homeServerConnectionConfig)
                .build()
    }
}
