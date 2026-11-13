/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.onboarding.gateway

import im.vector.app.core.utils.ensureProtocol
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

data class GatewayService(
        val id: String,
        val name: String,
        val homeservers: List<String>,
        val usernameCheck: String,
        val requiresEmail: Boolean,
        val readiness: String,
        val readinessReason: String?,
) {
    val isReady: Boolean get() = readiness == READY
    val supportsUsernameCheck: Boolean get() = usernameCheck == "best_effort" || usernameCheck == "authoritative"

    fun lists(serverNames: Collection<String>): String? =
            homeservers.firstOrNull { listed -> serverNames.any { it.equals(listed, ignoreCase = true) } }

    companion object {
        const val READY = "ready"
    }
}

data class GatewayError(
        val code: String,
        val message: String,
        val retryable: Boolean,
        val retryAfterMs: Long? = null,
)

data class GatewayResult(
        val userId: String?,
        val authenticationStatus: String,
        val accessToken: String?,
)

data class GatewayOperation(
        val id: String,
        val status: String,
        val stage: String?,
        val accountCreated: Boolean?,
        val result: GatewayResult?,
        val error: GatewayError?,
) {
    val isPending: Boolean get() = status == STATUS_QUEUED || status == STATUS_RUNNING

    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_RUNNING = "running"
        const val STATUS_REQUIRES_ACTION = "requires_action"
        const val STATUS_SUCCEEDED = "succeeded"
        const val STATUS_INDETERMINATE = "indeterminate"

        const val AUTH_AUTHENTICATED = "authenticated"
    }
}

enum class GatewayUsernameAvailability { AVAILABLE, TAKEN, INVALID, UNKNOWN }

/** Protocol and gateway error codes, plus a few of the client's own for failures it detects itself. */
object GatewayErrorCode {
    const val UNSUPPORTED_HOMESERVER = "UNSUPPORTED_HOMESERVER"
    const val FIELD_REQUIRED = "FIELD_REQUIRED"
    const val INVALID_FIELD = "INVALID_FIELD"
    const val USERNAME_TAKEN = "USERNAME_TAKEN"
    const val INVALID_USERNAME = "INVALID_USERNAME"
    const val SERVICE_NOT_READY = "SERVICE_NOT_READY"
    const val CAPTCHA_UNSOLVED = "CAPTCHA_UNSOLVED"
    const val SERVICE_BUSY = "SERVICE_BUSY"
    const val SERVICE_COOLDOWN = "SERVICE_COOLDOWN"
    const val UPSTREAM_ERROR = "UPSTREAM_ERROR"
    const val REGISTRATION_REJECTED = "REGISTRATION_REJECTED"
    const val INTERRUPTED = "INTERRUPTED"
    const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val NOT_FOUND = "NOT_FOUND"
    const val GATEWAY_BUSY = "GATEWAY_BUSY"
    const val GATEWAY_CLOSED = "GATEWAY_CLOSED"
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val REQUEST_TOO_LARGE = "REQUEST_TOO_LARGE"
    const val INVALID_IDEMPOTENCY_KEY = "INVALID_IDEMPOTENCY_KEY"
    const val IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT"

    const val NOT_CONFIGURED = "NOT_CONFIGURED"
    const val TRANSPORT_FAILED = "TRANSPORT_FAILED"
    const val INVALID_URL = "INVALID_URL"
    const val NOT_A_GATEWAY = "NOT_A_GATEWAY"
    const val HTTP_ERROR = "HTTP_ERROR"
    const val UNSUPPORTED_ACTION = "UNSUPPORTED_ACTION"
    const val LOGIN_FAILED = "LOGIN_FAILED"
}

/** An error the gateway reported, either for a refused request or for a failed operation. */
class RegistrationGatewayException(
        val error: GatewayError,
        val httpStatus: Int? = null,
        /** As reported by the operation; null when creation may have happened. False for refused requests. */
        val accountCreated: Boolean? = false,
        cause: Throwable? = null,
) : Exception(error.message, cause) {
    val mayHaveCreatedAccount: Boolean get() = accountCreated != false
}

object RegistrationGatewayWire {

    fun parseServices(body: String): List<GatewayService> {
        val array = JSONObject(body).optJSONArray("services") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::parseService) }
    }

    fun parseService(json: JSONObject): GatewayService? {
        val id = json.optStringOrNull("id") ?: return null
        val readiness = json.optJSONObject("readiness")
        return GatewayService(
                id = id,
                name = json.optStringOrNull("name") ?: id,
                homeservers = json.optJSONArray("homeservers").strings(),
                usernameCheck = json.optStringOrNull("username_check") ?: "unsupported",
                requiresEmail = json.optJSONObject("registration_fields")?.optJSONObject("email")?.optStringOrNull("requirement") == "required",
                // Gateways without a readiness block have nothing to prepare.
                readiness = readiness?.optStringOrNull("status") ?: GatewayService.READY,
                readinessReason = readiness?.optStringOrNull("reason"),
        )
    }

    fun parseOperation(body: String): GatewayOperation {
        val json = JSONObject(body)
        val result = json.optJSONObject("result")
        val authentication = result?.optJSONObject("authentication")
        return GatewayOperation(
                id = json.getString("id"),
                status = json.getString("status"),
                stage = json.optStringOrNull("stage"),
                accountCreated = if (json.isNull("account_created")) null else json.optBoolean("account_created"),
                result = result?.let {
                    GatewayResult(
                            userId = it.optStringOrNull("user_id"),
                            authenticationStatus = authentication?.optStringOrNull("status").orEmpty(),
                            accessToken = authentication?.optJSONObject("session")?.optStringOrNull("access_token"),
                    )
                },
                error = json.optJSONObject("error")?.let(::parseErrorObject),
        )
    }

    fun parseUsernameAvailability(body: String): GatewayUsernameAvailability = when (JSONObject(body).optString("availability")) {
        "available" -> GatewayUsernameAvailability.AVAILABLE
        "taken" -> GatewayUsernameAvailability.TAKEN
        "invalid" -> GatewayUsernameAvailability.INVALID
        else -> GatewayUsernameAvailability.UNKNOWN
    }

    /** The `{"error":{...}}` body of a refused request, or null if the body isn't one. */
    fun parseErrorBody(body: String): GatewayError? =
            runCatching { JSONObject(body).optJSONObject("error")?.let(::parseErrorObject) }.getOrNull()

    private fun parseErrorObject(json: JSONObject) = GatewayError(
            code = json.optStringOrNull("code") ?: "UNKNOWN",
            message = json.optString("message"),
            retryable = json.optBoolean("retryable"),
            retryAfterMs = if (json.has("retry_after_ms") && !json.isNull("retry_after_ms")) json.optLong("retry_after_ms") else null,
    )

    fun registrationBody(homeserver: String, username: String, password: String): String = JSONObject()
            .put("homeserver", homeserver)
            .put("username", username)
            .put("password", password)
            .toString()

    fun usernameCheckBody(homeserver: String, username: String): String = JSONObject()
            .put("homeserver", homeserver)
            .put("username", username)
            .toString()

    /**
     * The first service listing any of [serverNames], preferring one that is ready, with the listed name.
     * Services that require an email address are skipped: only username and password sign-up is supported.
     */
    fun pickService(services: List<GatewayService>, serverNames: Collection<String>): Pair<GatewayService, String>? = services
            .filterNot { it.requiresEmail }
            .mapNotNull { service -> service.lists(serverNames)?.let { service to it } }
            .sortedByDescending { it.first.isReady }
            .firstOrNull()

    /** Each of [segments] is one path segment, appended after any path prefix in [baseUrl]. */
    fun endpoint(baseUrl: String, vararg segments: String): HttpUrl? =
            HttpUrl.parse(baseUrl.ensureProtocol())?.newBuilder()?.apply { segments.forEach { addPathSegment(it) } }?.build()

    /** Server names a homeserver may be listed under: each URL's host, plus host:port for a non-default port. */
    fun serverNameCandidates(vararg urls: String?): List<String> = urls
            .mapNotNull { url -> url?.let { HttpUrl.parse(it.ensureProtocol()) } }
            .flatMap { url ->
                listOfNotNull(url.host(), "${url.host()}:${url.port()}".takeIf { url.port() != HttpUrl.defaultPort(url.scheme()) })
            }
            .distinct()

    private fun JSONObject.optStringOrNull(name: String): String? =
            if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

    private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }
}
