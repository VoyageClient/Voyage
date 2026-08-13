/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.session

import im.vector.app.ActiveSessionDataSource
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.dispatchers.CoroutineDispatchers
import im.vector.app.core.pushers.UnifiedPushHelper
import im.vector.app.core.resources.StringProvider
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.android.sdk.api.auth.AuthenticationService
import timber.log.Timber
import javax.inject.Inject

/**
 * Requests a switch to the given session. The actual session swap happens on the next app init
 * (see [ActiveSessionHolder.applyPendingRelease] and [SessionInitializer]). Callers should
 * follow this with [MainActivity.restartApp] so that the current Activity tears down before
 * the previous session is closed — that avoids racing with Fragment-side Realm observers.
 *
 * Throws [NoSuchElementException] when [sessionId] is not in the local credentials store.
 */
class SwitchAccountUseCase @Inject constructor(
        private val authenticationService: AuthenticationService,
        private val activeSessionHolder: ActiveSessionHolder,
        private val lastActiveSessionStore: LastActiveSessionStore,
        private val coroutineDispatchers: CoroutineDispatchers,
        private val activeSessionDataSource: ActiveSessionDataSource,
        private val unifiedPushHelper: UnifiedPushHelper,
        private val stringProvider: StringProvider,
        private val cancelSessionWorkUseCase: CancelSessionWorkUseCase,
) {
    suspend fun execute(sessionId: String) = withContext(coroutineDispatchers.io) {
        authenticationService.getSessionParams(sessionId)
                ?: throw NoSuchElementException("No stored session with id $sessionId")
        // In-memory read only: getSafeActiveSession() would (re)initialize a session, which the
        // post-signout auto-switch must not do
        val previous = activeSessionDataSource.currentValue?.orNull()
        if (previous != null && previous.sessionId != sessionId) {
            // Only the active account syncs: leaving the old pusher registered would keep its
            // homeserver pushing this device, and those pushes get handled with the new session.
            // Best-effort — never block the switch on it.
            runCatching {
                withTimeoutOrNull(PUSHER_UNREGISTER_TIMEOUT_MS) {
                    unifiedPushHelper.getEndpointOrToken()?.let { pushKey ->
                        previous.pushersService().removeHttpPusher(
                                pushKey,
                                stringProvider.getString(im.vector.app.config.R.string.pusher_app_id)
                        )
                    }
                }
            }.onFailure { Timber.w(it, "Failed to unregister pusher before switching account") }
            cancelSessionWorkUseCase.execute(previous.sessionId)
        }
        lastActiveSessionStore.set(sessionId)
        activeSessionHolder.softClearForSwitch()
    }

    companion object {
        private const val PUSHER_UNREGISTER_TIMEOUT_MS = 5_000L
    }
}
