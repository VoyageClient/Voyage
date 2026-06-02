/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.session

import im.vector.app.core.dispatchers.CoroutineDispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.matrix.android.sdk.api.auth.AuthenticationService
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

class LogoutAccountUseCase @Inject constructor(
        private val authenticationService: AuthenticationService,
        private val lastActiveSessionStore: LastActiveSessionStore,
        private val coroutineDispatchers: CoroutineDispatchers,
        private val accountInfoCache: AccountInfoCache,
) {

    sealed class Result {
        object SignedOutCleanly : Result()
        object ServerUnreachable : Result()
        object NotFound : Result()
    }

    /**
     * Probe the homeserver's logout endpoint directly. If we get any HTTP response back at all,
     * we treat it as "server reached" and proceed with local cleanup (the SDK's signOut would do
     * the same things internally, but going via the high-level path is fragile for sessions that
     * were never [Session.open]-ed). Only an actual IO failure or a timeout maps to
     * [Result.ServerUnreachable].
     */
    suspend fun tryServerSignOut(sessionId: String): Result = withContext(coroutineDispatchers.io) {
        val sessionParams = authenticationService.getSessionParams(sessionId)
                ?: return@withContext Result.NotFound
        val session = authenticationService.getOrCreateSession(sessionParams)
        val homeServerBase = sessionParams.homeServerUrlBase.trimEnd('/')
        val accessToken = sessionParams.credentials.accessToken
        val request = Request.Builder()
                .url("$homeServerBase/_matrix/client/v3/logout")
                .post("{}".toRequestBody(JSON))
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

        val reached = withTimeoutOrNull(SERVER_SIGNOUT_TIMEOUT_MS) {
            try {
                session.getOkHttpClient().newCall(request).execute().use { resp ->
                    Timber.d("tryServerSignOut $sessionId: HTTP ${resp.code}")
                    true
                }
            } catch (io: IOException) {
                Timber.w(io, "tryServerSignOut $sessionId: network failure")
                false
            }
        } ?: false

        if (!reached) return@withContext Result.ServerUnreachable

        // Server-side device is revoked (or the token was already invalid). Do the local cleanup
        // via the SDK so Realm/files/crypto are torn down properly, ignoring any further server
        // chatter.
        runCatching { session.signOutService().signOut(signOutFromHomeserver = false, ignoreServerRequestError = true) }
                .onFailure {
                    Timber.w(it, "tryServerSignOut $sessionId: local cleanup failed, deleting params directly")
                    authenticationService.deleteSessionLocally(sessionId)
                }
        finalizeRemoval(sessionId)
        Result.SignedOutCleanly
    }

    /**
     * Local-only sign-out: clears credentials without contacting the homeserver. Use after the
     * user confirms they want to proceed despite [Result.ServerUnreachable].
     */
    suspend fun forceLocalSignOut(sessionId: String) = withContext(coroutineDispatchers.io) {
        val sessionParams = authenticationService.getSessionParams(sessionId) ?: return@withContext
        val session = authenticationService.getOrCreateSession(sessionParams)
        runCatching { session.signOutService().signOut(signOutFromHomeserver = false, ignoreServerRequestError = true) }
                .onFailure {
                    Timber.w(it, "logoutAccount: local sign-out failed for $sessionId, deleting params directly")
                    authenticationService.deleteSessionLocally(sessionId)
                }
        finalizeRemoval(sessionId)
    }

    private suspend fun finalizeRemoval(sessionId: String) {
        if (lastActiveSessionStore.get() == sessionId) lastActiveSessionStore.set(null)
        accountInfoCache.delete(sessionId)
    }

    companion object {
        private const val SERVER_SIGNOUT_TIMEOUT_MS = 10_000L
        private val JSON = "application/json".toMediaType()
    }
}
