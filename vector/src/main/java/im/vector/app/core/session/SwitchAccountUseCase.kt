/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.session

import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.dispatchers.CoroutineDispatchers
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.auth.AuthenticationService
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
) {
    suspend fun execute(sessionId: String) = withContext(coroutineDispatchers.io) {
        authenticationService.getSessionParams(sessionId)
                ?: throw NoSuchElementException("No stored session with id $sessionId")
        lastActiveSessionStore.set(sessionId)
        activeSessionHolder.softClearForSwitch()
    }
}
