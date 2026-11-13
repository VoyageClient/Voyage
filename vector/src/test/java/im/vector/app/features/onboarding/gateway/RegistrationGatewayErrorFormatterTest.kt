/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.onboarding.gateway

import im.vector.app.test.fakes.FakeErrorFormatter
import im.vector.app.test.fakes.FakeStringProvider
import im.vector.app.test.fakes.toTestString
import im.vector.lib.strings.CommonStrings
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.json.JSONException
import org.junit.Test
import java.net.ConnectException
import java.net.UnknownHostException

class RegistrationGatewayErrorFormatterTest {

    private val errorFormatter = FakeErrorFormatter()
    private val settings = mockk<RegistrationGatewaySettings> { every { token } returns "a-token" }
    private val formatter = RegistrationGatewayErrorFormatter(FakeStringProvider().instance, errorFormatter, settings)

    @Test
    fun `client-detected codes get sentences instead of the code`() {
        formatter.format(gatewayFailure(GatewayErrorCode.NOT_CONFIGURED)) shouldBeEqualTo CommonStrings.gateway_error_not_configured.toTestString()
    }

    @Test
    fun `protocol codes are described by the app, not by the gateway's message`() {
        formatter.format(gatewayFailure(GatewayErrorCode.UNAUTHORIZED, "A valid bearer token is required")) shouldBeEqualTo
                CommonStrings.gateway_error_unauthorized.toTestString()
    }

    @Test
    fun `an email demand reads as unsupported`() {
        formatter.format(gatewayFailure(GatewayErrorCode.FIELD_REQUIRED, "This service requires email")) shouldBeEqualTo
                CommonStrings.gateway_error_email_unsupported.toTestString()
        formatter.format(gatewayFailure(GatewayErrorCode.UNSUPPORTED_ACTION)) shouldBeEqualTo CommonStrings.gateway_error_email_unsupported.toTestString()
    }

    @Test
    fun `a 401 without a token asks for one`() {
        every { settings.token } returns ""

        formatter.format(gatewayFailure(GatewayErrorCode.UNAUTHORIZED)) shouldBeEqualTo CommonStrings.gateway_error_token_missing.toTestString()
    }

    @Test
    fun `transport failures name what went wrong`() {
        formatter.format(UnknownHostException("gw.example")) shouldBeEqualTo CommonStrings.gateway_error_unknown_host.toTestString()
        formatter.format(ConnectException("refused")) shouldBeEqualTo CommonStrings.gateway_error_connect.toTestString()
        val wrapped = RegistrationGatewayException(
                GatewayError(GatewayErrorCode.TRANSPORT_FAILED, "", retryable = true),
                accountCreated = null,
                cause = ConnectException("refused"),
        )
        formatter.format(wrapped) shouldBeEqualTo CommonStrings.gateway_error_connect.toTestString()
    }

    @Test
    fun `a page that is not a gateway is reported as such`() {
        formatter.format(JSONException("Value <html> cannot be converted")) shouldBeEqualTo CommonStrings.gateway_error_not_a_gateway.toTestString()
        formatter.format(RegistrationGatewayException(GatewayError(GatewayErrorCode.HTTP_ERROR, "", false), httpStatus = 404)) shouldBeEqualTo
                CommonStrings.gateway_error_not_a_gateway.toTestString()
    }

    @Test
    fun `unknown codes fall back to the gateway's message, then the code`() {
        formatter.format(gatewayFailure("SOMETHING_NEW", "The upstream form changed")) shouldBeEqualTo "The upstream form changed"
        formatter.format(gatewayFailure("SOMETHING_NEW")) shouldBeEqualTo "test-${CommonStrings.gateway_error_unknown}-[SOMETHING_NEW]"
    }

    @Test
    fun `a failed sign-in after creation includes the sign-in error`() {
        val cause = IllegalStateException("Invalid password")
        errorFormatter.given(cause, "Incorrect username or password")
        val failure = RegistrationGatewayException(GatewayError(GatewayErrorCode.LOGIN_FAILED, "", false), accountCreated = true, cause = cause)

        formatter.format(failure) shouldBeEqualTo "test-${CommonStrings.ftue_auth_gateway_login_failed}-[Incorrect username or password]"
    }

    private fun gatewayFailure(code: String, message: String = "") = RegistrationGatewayException(GatewayError(code, message, retryable = false))
}
