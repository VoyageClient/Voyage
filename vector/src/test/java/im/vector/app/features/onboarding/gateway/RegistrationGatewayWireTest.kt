/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.onboarding.gateway

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.json.JSONObject
import org.junit.Test

class RegistrationGatewayWireTest {

    @Test
    fun `parses discovery with readiness and field requirements`() {
        val services = RegistrationGatewayWire.parseServices(
                """
                {"protocol_version":"1","services":[{
                  "id":"example","name":"Example","homeservers":["example.org"],
                  "completion_modes":["login_required"],"username_check":"unsupported",
                  "registration_fields":{"username":{"requirement":"required"},"password":{"requirement":"required"},
                    "email":{"requirement":"unsupported"}},
                  "email_verification":{"methods":[]},
                  "readiness":{"status":"unavailable","reason":"Not set up yet"}
                }]}
                """.trimIndent()
        )

        services shouldBeEqualTo listOf(
                GatewayService(
                        id = "example",
                        name = "Example",
                        homeservers = listOf("example.org"),
                        usernameCheck = "unsupported",
                        requiresEmail = false,
                        readiness = "unavailable",
                        readinessReason = "Not set up yet",
                )
        )
        services.single().isReady shouldBeEqualTo false
    }

    @Test
    fun `empty discovery yields no services`() {
        RegistrationGatewayWire.parseServices("""{"protocol_version":"1","services":[]}""") shouldBeEqualTo emptyList()
    }

    @Test
    fun `matches listed homeservers case-insensitively and returns the listed spelling`() {
        val service = aService(homeservers = listOf("Example.org"))

        service.lists(listOf("matrix.example.org", "example.ORG")) shouldBeEqualTo "Example.org"
        service.lists(listOf("other.org")).shouldBeNull()
    }

    @Test
    fun `server name candidates include hosts and non-default ports`() {
        RegistrationGatewayWire.serverNameCandidates("https://example.org/", "https://matrix.example.org:8448", null, "example.org") shouldBeEqualTo
                listOf("example.org", "matrix.example.org", "matrix.example.org:8448")
    }

    @Test
    fun `endpoints keep the base path prefix and encode ids as single segments`() {
        RegistrationGatewayWire.endpoint("https://example.com:8443", "v1", "services").toString() shouldBeEqualTo
                "https://example.com:8443/v1/services"
        RegistrationGatewayWire.endpoint("example.com/gateway/", "v1", "registrations", "reg/1").toString() shouldBeEqualTo
                "https://example.com/gateway/v1/registrations/reg%2F1"
        RegistrationGatewayWire.endpoint("http://", "v1").shouldBeNull()
    }

    @Test
    fun `parses a required email`() {
        val services = RegistrationGatewayWire.parseServices(
                """{"services":[{"id":"a","homeservers":["example.org"],"registration_fields":{"email":{"requirement":"required"}}}]}"""
        )

        services.single().requiresEmail shouldBeEqualTo true
    }

    @Test
    fun `picks a ready service and skips ones that require email`() {
        val needsEmail = aService(homeservers = listOf("example.org")).copy(id = "email", requiresEmail = true)
        val notReady = aService(homeservers = listOf("example.org")).copy(id = "preparing", readiness = "preparing")
        val ready = aService(homeservers = listOf("example.org")).copy(id = "ready")

        RegistrationGatewayWire.pickService(listOf(needsEmail, notReady, ready), listOf("example.org")) shouldBeEqualTo (ready to "example.org")
        RegistrationGatewayWire.pickService(listOf(needsEmail, notReady), listOf("example.org")) shouldBeEqualTo (notReady to "example.org")
        RegistrationGatewayWire.pickService(listOf(needsEmail), listOf("example.org")).shouldBeNull()
    }

    @Test
    fun `parses a pending action as not pending`() {
        val operation = RegistrationGatewayWire.parseOperation(
                """
                {"id":"reg_example","status":"requires_action","stage":"email_verification","account_created":false,
                 "action":{"id":"act_example","type":"email_verification","method":"code"}}
                """.trimIndent()
        )

        operation.status shouldBeEqualTo GatewayOperation.STATUS_REQUIRES_ACTION
        operation.isPending shouldBeEqualTo false
        operation.accountCreated shouldBeEqualTo false
    }

    @Test
    fun `parses an authenticated result`() {
        val operation = RegistrationGatewayWire.parseOperation(
                """
                {"id":"reg_example","status":"succeeded","stage":"completed","account_created":true,
                 "result":{"homeserver":"example.org","user_id":"@alice:example.org",
                   "authentication":{"status":"authenticated","session":{"access_token":"secret","device_id":"DEV"}}}}
                """.trimIndent()
        )

        operation.result shouldBeEqualTo GatewayResult(
                userId = "@alice:example.org",
                authenticationStatus = GatewayOperation.AUTH_AUTHENTICATED,
                accessToken = "secret",
        )
    }

    @Test
    fun `null account_created means the outcome may be unknown`() {
        val operation = RegistrationGatewayWire.parseOperation(
                """
                {"id":"reg_example","status":"indeterminate","account_created":null,
                 "error":{"code":"OUTCOME_UNKNOWN","message":"Account creation may have happened","retryable":false}}
                """.trimIndent()
        )

        operation.accountCreated.shouldBeNull()
        operation.error?.code shouldBeEqualTo GatewayErrorCode.OUTCOME_UNKNOWN
        RegistrationGatewayException(operation.error!!, accountCreated = operation.accountCreated).mayHaveCreatedAccount shouldBeEqualTo true
    }

    @Test
    fun `parses refused-request error bodies`() {
        RegistrationGatewayWire.parseErrorBody(
                """{"error":{"code":"FIELD_REQUIRED","message":"This service requires email","retryable":false,"retry_after_ms":1500}}"""
        ) shouldBeEqualTo GatewayError("FIELD_REQUIRED", "This service requires email", retryable = false, retryAfterMs = 1500)
        RegistrationGatewayWire.parseErrorBody("<html>Bad gateway</html>").shouldBeNull()
    }

    @Test
    fun `registration body sends no email`() {
        val body = JSONObject(RegistrationGatewayWire.registrationBody("example.org", "alice", "pw"))

        body.getString("username") shouldBeEqualTo "alice"
        body.has("email") shouldBeEqualTo false
    }

    private fun aService(homeservers: List<String>) = GatewayService(
            id = "id",
            name = "name",
            homeservers = homeservers,
            usernameCheck = "unsupported",
            requiresEmail = false,
            readiness = GatewayService.READY,
            readinessReason = null,
    )
}
