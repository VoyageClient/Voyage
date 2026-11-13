/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.onboarding.gateway

import im.vector.app.core.vpn.VpnGateInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for the registration gateway protocol (version 1). Uses its own OkHttp client so the
 * Matrix session's interceptors never send a homeserver access token to the gateway.
 */
@Singleton
class RegistrationGatewayClient @Inject constructor(
        private val settings: RegistrationGatewaySettings,
        vpnGateInterceptor: VpnGateInterceptor,
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(vpnGateInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()

    private class Response(val body: String, val retryAfterMs: Long?) {
        /** A 200 that isn't the JSON we expect means the URL points at something other than a gateway. */
        fun <T> parse(parser: (String) -> T): T = try {
            parser(body)
        } catch (e: JSONException) {
            throw RegistrationGatewayException(GatewayError(GatewayErrorCode.NOT_A_GATEWAY, "", retryable = false), cause = e)
        }
    }

    suspend fun services(): List<GatewayService> =
            execute(request("v1", "services").get()).parse(RegistrationGatewayWire::parseServices)

    suspend fun service(id: String): GatewayService? = try {
        execute(request("v1", "services", id).get()).parse { RegistrationGatewayWire.parseService(JSONObject(it)) }
    } catch (e: RegistrationGatewayException) {
        if (e.error.code == GatewayErrorCode.NOT_FOUND) null else throw e
    }

    suspend fun findService(serverNames: Collection<String>): Pair<GatewayService, String>? {
        if (!settings.isConfigured || serverNames.isEmpty()) return null
        return RegistrationGatewayWire.pickService(services(), serverNames)
    }

    suspend fun checkUsername(homeserver: String, username: String): GatewayUsernameAvailability {
        val body = RegistrationGatewayWire.usernameCheckBody(homeserver, username)
        return execute(request("v1", "username-checks").post(json(body))).parse(RegistrationGatewayWire::parseUsernameAvailability)
    }

    /**
     * Retries transport failures and a full queue with the same idempotency key and body, as the
     * protocol allows; a retry can only ever return the operation the first attempt created.
     */
    suspend fun startRegistration(homeserver: String, username: String, password: String): GatewayOperation {
        val body = RegistrationGatewayWire.registrationBody(homeserver, username, password)
        val idempotencyKey = UUID.randomUUID().toString()
        return withRetries {
            val request = request("v1", "registrations").header("Idempotency-Key", idempotencyKey).post(json(body))
            execute(request).parse(RegistrationGatewayWire::parseOperation)
        }
    }

    /**
     * Polls until the operation leaves `queued`/`running`. Transport failures only stop the polling
     * after several in a row, since the operation keeps running on the gateway regardless.
     */
    suspend fun awaitOperation(operationId: String, onProgress: (GatewayOperation) -> Unit): GatewayOperation {
        var polls = 0
        var failures = 0
        while (true) {
            val response = try {
                execute(request("v1", "registrations", operationId).get())
            } catch (e: IOException) {
                if (++failures >= MAX_POLL_FAILURES) throw e
                Timber.w(e, "Registration gateway poll failed ($failures)")
                delay(POLL_FAILURE_DELAY_MS * failures)
                continue
            }
            failures = 0
            val operation = response.parse(RegistrationGatewayWire::parseOperation)
            if (!operation.isPending) return operation
            onProgress(operation)
            polls++
            val interval = if (polls < FAST_POLLS) POLL_INTERVAL_MS else SLOW_POLL_INTERVAL_MS
            delay(maxOf(interval, response.retryAfterMs ?: 0))
        }
    }

    private suspend fun <T> withRetries(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return block()
            } catch (e: IOException) {
                if (attempt >= MAX_SUBMIT_ATTEMPTS) throw e
                Timber.w(e, "Registration gateway request failed, retrying")
                delay(SUBMIT_RETRY_DELAY_MS * attempt)
            } catch (e: RegistrationGatewayException) {
                if (attempt >= MAX_SUBMIT_ATTEMPTS || e.httpStatus != 503 || !e.error.retryable) throw e
                delay(maxOf(SUBMIT_RETRY_DELAY_MS * attempt, e.error.retryAfterMs ?: 0))
            }
        }
    }

    private fun request(vararg segments: String): Request.Builder {
        val configured = settings.url
        if (configured.isEmpty()) throw RegistrationGatewayException(GatewayError(GatewayErrorCode.NOT_CONFIGURED, "", retryable = false))
        val url = RegistrationGatewayWire.endpoint(configured, *segments)
                ?: throw RegistrationGatewayException(GatewayError(GatewayErrorCode.INVALID_URL, "", retryable = false))
        return Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .apply { settings.token.takeIf { it.isNotEmpty() }?.let { header("Authorization", "Bearer $it") } }
    }

    private suspend fun execute(builder: Request.Builder): Response = withContext(Dispatchers.IO) {
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body()?.string().orEmpty()
            val retryAfterMs = response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
            if (!response.isSuccessful) {
                val error = RegistrationGatewayWire.parseErrorBody(body)
                        ?: GatewayError(GatewayErrorCode.HTTP_ERROR, "", retryable = false)
                throw RegistrationGatewayException(
                        error.copy(retryAfterMs = error.retryAfterMs ?: retryAfterMs),
                        httpStatus = response.code(),
                )
            }
            Response(body, retryAfterMs)
        }
    }

    private fun json(body: String) = RequestBody.create(JSON, body)

    companion object {
        private val JSON = MediaType.parse("application/json; charset=utf-8")
        private const val MAX_SUBMIT_ATTEMPTS = 3
        private const val SUBMIT_RETRY_DELAY_MS = 2_000L
        private const val MAX_POLL_FAILURES = 10
        private const val POLL_FAILURE_DELAY_MS = 1_000L
        private const val POLL_INTERVAL_MS = 1_000L
        private const val SLOW_POLL_INTERVAL_MS = 2_000L
        private const val FAST_POLLS = 30
    }
}
