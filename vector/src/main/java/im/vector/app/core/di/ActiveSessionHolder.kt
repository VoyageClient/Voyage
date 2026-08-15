/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.di

import im.vector.app.ActiveSessionDataSource
import im.vector.app.core.dispatchers.CoroutineDispatchers
import im.vector.app.core.pushers.UnregisterUnifiedPushUseCase
import im.vector.app.core.services.GuardServiceStarter
import im.vector.app.core.session.ConfigureAndStartSessionUseCase
import im.vector.app.core.session.LastActiveSessionStore
import im.vector.app.core.vpn.VpnGateState
import im.vector.app.features.crypto.keysrequest.KeyRequestHandler
import im.vector.app.features.crypto.verification.IncomingVerificationRequestHandler
import im.vector.app.features.home.ShortcutsHandler
import im.vector.app.features.home.room.detail.timeline.helper.AudioMessagePlaybackTracker
import im.vector.app.features.media.MediaContentRevealManager
import im.vector.app.features.media.SendingMediaGate
import im.vector.app.features.notifications.NotificationDrawerManager
import im.vector.app.features.pgp.PgpServiceManager
import im.vector.app.features.notifications.PushRuleTriggerListener
import im.vector.app.features.popup.PopupAlertManager
import im.vector.app.features.redaction.preservation.RedactedContentRepository
import im.vector.app.features.redaction.preservation.RedactionPreservationService
import im.vector.app.features.session.SessionListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.matrix.android.sdk.api.auth.AuthenticationService
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOption
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class ActiveSessionHolder @Inject constructor(
        private val activeSessionDataSource: ActiveSessionDataSource,
        private val keyRequestHandler: KeyRequestHandler,
        private val incomingVerificationRequestHandler: IncomingVerificationRequestHandler,
        private val pushRuleTriggerListener: PushRuleTriggerListener,
        // Provider: these reach ActiveSessionHolder themselves, so a direct injection would cycle.
        private val redactionPreservationService: Provider<RedactionPreservationService>,
        private val redactedContentRepository: Provider<RedactedContentRepository>,
        private val notificationDrawerManager: Provider<NotificationDrawerManager>,
        private val shortcutsHandler: Provider<ShortcutsHandler>,
        private val popupAlertManager: PopupAlertManager,
        private val audioMessagePlaybackTracker: Provider<AudioMessagePlaybackTracker>,
        private val mediaContentRevealManager: Provider<MediaContentRevealManager>,
        private val sendingMediaGate: Provider<SendingMediaGate>,
        private val pgpServiceManager: Provider<PgpServiceManager>,
        private val sessionListener: SessionListener,
        private val imageManager: ImageManager,
        private val guardServiceStarter: GuardServiceStarter,
        private val sessionInitializer: SessionInitializer,
        private val authenticationService: AuthenticationService,
        private val configureAndStartSessionUseCase: ConfigureAndStartSessionUseCase,
        private val unregisterUnifiedPushUseCase: UnregisterUnifiedPushUseCase,
        private val applicationCoroutineScope: CoroutineScope,
        private val coroutineDispatchers: CoroutineDispatchers,
        private val lastActiveSessionStore: LastActiveSessionStore,
        private val vpnGateState: VpnGateState,
) {

    private var activeSessionReference: AtomicReference<Session?> = AtomicReference()
    private val pendingReleaseSessionIds = java.util.Collections.newSetFromMap<String>(java.util.concurrent.ConcurrentHashMap())

    fun setActiveSession(session: Session) {
        Timber.w("setActiveSession of ${session.myUserId}")
        val previous = activeSessionReference.get()
        if (previous != null && previous.sessionId != session.sessionId) {
            // Detach the previous session's listeners and queue it for release, but skip the
            // "empty" data-source emission — we're about to publish the new session anyway,
            // avoiding observer flicker.
            detachAndPendForRelease(previous)
            stopAppLevelHandlers()
        }
        activeSessionReference.set(session)
        activeSessionDataSource.post(session.toOption())
        lastActiveSessionStore.set(session.sessionId)

        keyRequestHandler.start(session)
        incomingVerificationRequestHandler.start(session)
        session.addListener(sessionListener)
        pushRuleTriggerListener.startWithSession(session)
        imageManager.onSessionStarted(session)
        guardServiceStarter.start()
    }

    suspend fun clearActiveSession() {
        // Do some cleanup first
        getSafeActiveSession()?.let {
            Timber.w("clearActiveSession of ${it.myUserId}")
            it.removeListener(sessionListener)
            vpnGateState.dropQueuedFor(it.sessionId)
        }

        activeSessionReference.set(null)
        activeSessionDataSource.post(Optional.empty())
        lastActiveSessionStore.set(null)

        keyRequestHandler.stop()
        incomingVerificationRequestHandler.stop()
        pushRuleTriggerListener.stop()
        configureAndStartSessionUseCase.cancelProfileObserver()
        unregisterUnifiedPushUseCase.execute(pushersManager = null)
        guardServiceStarter.stop()
    }

    fun hasActiveSession(): Boolean {
        return activeSessionReference.get() != null || authenticationService.hasAuthenticatedSessions()
    }

    fun getSafeActiveSession(): Session? {
        return runBlocking { getOrInitializeSession() }
    }

    fun getSafeActiveSessionAsync(withSession: ((Session?) -> Unit)) {
        applicationCoroutineScope.launch(coroutineDispatchers.io) {
            val session = getOrInitializeSession()
            withSession(session)
        }
    }

    fun getActiveSession(): Session {
        return getSafeActiveSession()
                ?: throw IllegalStateException("You should authenticate before using this")
    }

    suspend fun getOrInitializeSession(): Session? {
        return activeSessionReference.get()
                ?: sessionInitializer.tryInitialize(readCurrentSession = { activeSessionReference.get() }) { session ->
                    setActiveSession(session)
                    configureAndStartSessionUseCase.execute(session, startSyncing = false)
                }
    }

    fun isWaitingForSessionInitialization() = activeSessionReference.get() == null && authenticationService.hasAuthenticatedSessions()

    /**
     * Used by [SwitchAccountUseCase] to detach the active session in preparation for an
     * activity restart. Listeners come down, the in-memory reference is cleared, and the
     * sessionId is queued for a deferred close+release ([applyPendingRelease]) which runs in
     * the next process init — by then any UI observers on the previous session's Realm are
     * gone, so closing it doesn't race with a query.
     */
    fun softClearForSwitch() {
        val previous = activeSessionReference.getAndSet(null)
        if (previous != null) detachAndPendForRelease(previous)
        activeSessionDataSource.post(Optional.empty())
        stopAppLevelHandlers()
        configureAndStartSessionUseCase.cancelProfileObserver()
    }

    private fun detachAndPendForRelease(previous: Session) {
        Timber.w("detachAndPendForRelease: ${previous.myUserId}")
        runCatching { previous.syncService().stopAnyBackgroundSync() }
        runCatching { previous.syncService().stopSync() }
        runCatching { previous.removeListener(sessionListener) }
        vpnGateState.dropQueuedFor(previous.sessionId)
        pendingReleaseSessionIds += previous.sessionId
    }

    private fun stopAppLevelHandlers() {
        keyRequestHandler.stop()
        incomingVerificationRequestHandler.stop()
        pushRuleTriggerListener.stop()
        // The drawer, launcher shortcuts and popup alerts are single-account: anything left over
        // would display the previous account's content and act on it with the next session.
        notificationDrawerManager.get().resetForAccountSwitch()
        shortcutsHandler.get().clearShortcuts()
        popupAlertManager.cancelAll()
        audioMessagePlaybackTracker.get().clearAllStates()
        mediaContentRevealManager.get().clearAll()
        sendingMediaGate.get().clearAll()
        pgpServiceManager.get().clearDecryptionCache()
        // Without this the app-scoped singletons keep the released Session (and its whole component
        // graph) alive, and go on serving the previous account's preserved content.
        redactionPreservationService.get().stop()
        redactedContentRepository.get().clearCaches()
    }

    suspend fun applyPendingRelease() {
        val ids = synchronized(pendingReleaseSessionIds) {
            val snapshot = pendingReleaseSessionIds.toList()
            pendingReleaseSessionIds.clear()
            snapshot
        }
        if (ids.isEmpty()) return
        ids.forEach { id ->
            Timber.w("applyPendingRelease: releasing $id")
            // releaseSession closes the session itself (and is a no-op when no component exists);
            // closing here first would double-dispatch onSessionStopped.
            runCatching { authenticationService.releaseSession(id) }
        }
    }
}
