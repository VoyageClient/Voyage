/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.onboarding.gateway

import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.resources.StringProvider
import im.vector.lib.strings.CommonStrings
import org.json.JSONException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.net.ssl.SSLException

class RegistrationGatewayErrorFormatter @Inject constructor(
        private val stringProvider: StringProvider,
        private val errorFormatter: ErrorFormatter,
        private val settings: RegistrationGatewaySettings,
) {

    fun format(failure: Throwable): String = when (failure) {
        is RegistrationGatewayException -> format(failure)
        is UnknownHostException -> string(CommonStrings.gateway_error_unknown_host)
        is ConnectException -> string(CommonStrings.gateway_error_connect)
        is SSLException -> string(CommonStrings.gateway_error_ssl)
        is InterruptedIOException -> string(CommonStrings.gateway_error_timeout)
        is IOException -> string(CommonStrings.gateway_error_network)
        is JSONException -> string(CommonStrings.gateway_error_not_a_gateway)
        else -> errorFormatter.toHumanReadable(failure)
    }

    private fun format(failure: RegistrationGatewayException): String {
        val error = failure.error
        return when (error.code) {
            GatewayErrorCode.TRANSPORT_FAILED -> format(failure.cause ?: IOException())
            GatewayErrorCode.NOT_CONFIGURED -> string(CommonStrings.gateway_error_not_configured)
            GatewayErrorCode.INVALID_URL -> string(CommonStrings.gateway_error_invalid_url)
            GatewayErrorCode.NOT_A_GATEWAY -> string(CommonStrings.gateway_error_not_a_gateway)
            // 401 covers both a missing and a wrong token.
            GatewayErrorCode.UNAUTHORIZED -> when {
                settings.token.isEmpty() -> string(CommonStrings.gateway_error_token_missing)
                else -> string(CommonStrings.gateway_error_unauthorized)
            }
            GatewayErrorCode.UNSUPPORTED_HOMESERVER -> string(CommonStrings.gateway_error_unsupported_homeserver)
            GatewayErrorCode.NOT_FOUND -> string(CommonStrings.gateway_error_not_found)
            GatewayErrorCode.FIELD_REQUIRED,
            GatewayErrorCode.UNSUPPORTED_ACTION -> string(CommonStrings.gateway_error_email_unsupported)
            GatewayErrorCode.INVALID_FIELD -> error.message.ifEmpty { string(CommonStrings.gateway_error_invalid_field) }
            GatewayErrorCode.USERNAME_TAKEN -> string(CommonStrings.login_signup_error_user_in_use)
            GatewayErrorCode.INVALID_USERNAME -> string(CommonStrings.ftue_auth_gateway_username_invalid)
            GatewayErrorCode.SERVICE_NOT_READY -> when {
                error.message.isEmpty() -> string(CommonStrings.gateway_error_not_ready)
                else -> stringProvider.getString(CommonStrings.gateway_error_not_ready_reason, error.message)
            }
            GatewayErrorCode.CAPTCHA_UNSOLVED -> string(CommonStrings.gateway_error_captcha_unsolved)
            GatewayErrorCode.SERVICE_BUSY,
            GatewayErrorCode.GATEWAY_BUSY -> string(CommonStrings.gateway_error_busy)
            GatewayErrorCode.SERVICE_COOLDOWN -> string(CommonStrings.gateway_error_cooldown)
            GatewayErrorCode.UPSTREAM_ERROR -> string(CommonStrings.gateway_error_upstream)
            GatewayErrorCode.REGISTRATION_REJECTED -> string(CommonStrings.gateway_error_rejected)
            GatewayErrorCode.INTERRUPTED -> string(CommonStrings.gateway_error_interrupted)
            GatewayErrorCode.GATEWAY_CLOSED -> string(CommonStrings.gateway_error_closed)
            GatewayErrorCode.OUTCOME_UNKNOWN -> string(CommonStrings.gateway_error_outcome_unknown)
            GatewayErrorCode.INTERNAL_ERROR -> string(CommonStrings.gateway_error_internal)
            GatewayErrorCode.INVALID_REQUEST,
            GatewayErrorCode.REQUEST_TOO_LARGE,
            GatewayErrorCode.INVALID_IDEMPOTENCY_KEY,
            GatewayErrorCode.IDEMPOTENCY_CONFLICT -> string(CommonStrings.gateway_error_bad_request)
            GatewayErrorCode.LOGIN_FAILED -> stringProvider.getString(
                    CommonStrings.ftue_auth_gateway_login_failed,
                    errorFormatter.toHumanReadable(failure.cause ?: failure)
            )
            GatewayErrorCode.HTTP_ERROR -> when (failure.httpStatus) {
                404 -> string(CommonStrings.gateway_error_not_a_gateway)
                else -> stringProvider.getString(CommonStrings.gateway_error_http, failure.httpStatus ?: 0)
            }
            // Codes added after protocol version 1: the gateway's own message is meant for people.
            else -> error.message.ifEmpty { stringProvider.getString(CommonStrings.gateway_error_unknown, error.code) }
        }
    }

    private fun string(res: Int) = stringProvider.getString(res)
}
