/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.settings.LightweightSettingsStorage
import org.matrix.android.sdk.internal.SessionManager
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.di.SessionId
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.SessionCoroutineScopeHolder
import org.matrix.android.sdk.internal.session.SessionListeners
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.dispatchTo
import org.matrix.android.sdk.internal.session.room.RoomAPI
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Synapse answers a shadow-banned account's sends with a made-up id ("$" + 43 random letters) and never
 * stores the event. Real ids are base64 hashes that are letters-only about once in 7700 sends, so only
 * those are worth asking the server about.
 */
@SessionScope
internal class ShadowBanDetector @Inject constructor(
        private val roomAPI: RoomAPI,
        private val localEchoRepository: LocalEchoRepository,
        private val sessionCoroutineScopeHolder: SessionCoroutineScopeHolder,
        private val sessionListeners: SessionListeners,
        private val sessionManager: SessionManager,
        private val lightweightSettingsStorage: LightweightSettingsStorage,
        @SessionId private val sessionId: String,
) {

    internal enum class Lookup { MISSING, FOUND, UNKNOWN }

    private val sendCounter = AtomicLong()

    // A check finishing late for an older send must not undo the verdict of a newer one.
    private var latestVerdictSend = 0L

    fun onEventSent(roomId: String, localEchoId: String, remoteEventId: String) {
        if (!lightweightSettingsStorage.isShadowBanDetectionEnabled()) return
        val send = sendCounter.incrementAndGet()
        if (!looksFabricated(remoteEventId)) {
            report(send, suspected = false)
            return
        }
        sessionCoroutineScopeHolder.scope.launch {
            when (lookUp(roomId, remoteEventId)) {
                Lookup.FOUND -> report(send, suspected = false)
                Lookup.UNKNOWN -> Unit
                Lookup.MISSING -> {
                    Timber.w("Sent event $remoteEventId does not exist in $roomId, the account looks shadow-banned")
                    localEchoRepository.updateSendState(localEchoId, roomId, SendState.UNDELIVERED, SHADOW_BANNED_DETAILS)
                    report(send, suspected = true)
                }
            }
        }
    }

    @Synchronized
    private fun report(send: Long, suspected: Boolean) {
        if (send < latestVerdictSend) return
        latestVerdictSend = send
        sessionManager.getSessionComponent(sessionId)?.session().dispatchTo(sessionListeners) { session, listener ->
            listener.onShadowBanSuspicionChanged(session, suspected)
        }
    }

    /**
     * MISSING only when the server says M_NOT_FOUND on every check and sync never delivered the event either.
     * Checked more than once, apart, so replication lag between Synapse workers can't make a real event look missing.
     */
    internal suspend fun lookUp(roomId: String, eventId: String): Lookup {
        for (delayMs in CHECK_DELAYS_MS) {
            delay(delayMs)
            if (localEchoRepository.isRemoteEventStored(roomId, eventId)) return Lookup.FOUND
            try {
                executeRequest(null) { roomAPI.getEvent(roomId, eventId) }
                return Lookup.FOUND
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                if (!failure.isEventNotFound()) return Lookup.UNKNOWN
            }
        }
        return Lookup.MISSING
    }

    // A bare 404 can come from a proxy or an unimplemented endpoint; only Synapse's own answer counts.
    private fun Throwable.isEventNotFound() = this is Failure.ServerError && error.code == MatrixError.M_NOT_FOUND

    companion object {
        private val FABRICATED_EVENT_ID = Regex("^\\$[A-Za-z]{43}$")
        private val CHECK_DELAYS_MS = listOf(3_000L, 15_000L)
        private val SHADOW_BANNED_DETAILS = MoshiProvider.providesMoshi().adapter(MatrixError::class.java)
                .toJson(MatrixError(code = MatrixError.CLIENT_SHADOW_BANNED, message = "The homeserver faked accepting this event"))

        fun looksFabricated(eventId: String) = FABRICATED_EVENT_ID.matches(eventId)
    }
}
