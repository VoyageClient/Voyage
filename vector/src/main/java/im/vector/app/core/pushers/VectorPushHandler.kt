/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.pushers

import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.pushers.model.PushData
import im.vector.app.core.resources.BuildMeta
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.vpn.VpnGateState
import im.vector.app.features.notifications.NotifiableEventResolver
import im.vector.app.features.notifications.NotifiableMessageEvent
import im.vector.app.features.notifications.NotificationActionIds
import im.vector.app.features.notifications.NotificationDrawerManager
import im.vector.app.features.notifications.SimpleNotifiableEvent
import im.vector.app.features.settings.VectorDataStore
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.core.utils.timer.Clock
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.auth.AuthenticationService
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.getTimelineEvent
import timber.log.Timber
import javax.inject.Inject

private val loggerTag = LoggerTag("Push", LoggerTag.SYNC)

class VectorPushHandler @Inject constructor(
        private val notificationDrawerManager: NotificationDrawerManager,
        private val notifiableEventResolver: NotifiableEventResolver,
        private val activeSessionHolder: ActiveSessionHolder,
        private val vectorPreferences: VectorPreferences,
        private val vectorDataStore: VectorDataStore,
        private val actionIds: NotificationActionIds,
        private val context: Context,
        private val buildMeta: BuildMeta,
        private val vpnGateState: VpnGateState,
        private val unifiedPushStore: UnifiedPushStore,
        private val pushRequestStore: PushRequestStore,
        private val stringProvider: StringProvider,
        private val authenticationService: AuthenticationService,
        private val clock: Clock,
) {

    private val coroutineScope = CoroutineScope(SupervisorJob())

    /**
     * Write the push down and let the worker fetch it.
     *
     * @return true when the push was queued, so the caller knows the wakelock is still needed.
     */
    suspend fun handle(pushData: PushData, providerInfo: String): Boolean {
        Timber.tag(loggerTag.value).d("## handling pushData")
        DebugLog.i { "NOTIFDBG push received event=${pushData.eventId} room=${pushData.roomId} unread=${pushData.unread}" }

        if (buildMeta.lowPrivacyLoggingEnabled) {
            Timber.tag(loggerTag.value).d("## pushData: $pushData")
        }

        coroutineScope.launch(Dispatchers.IO) {
            vectorDataStore.incrementPushCounter()
        }

        // Diagnostic Push
        if (pushData.eventId == PushersManager.TEST_EVENT_ID) {
            val intent = Intent(actionIds.push)
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
            return false
        }

        if (!vectorPreferences.areNotificationEnabledForDevice()) {
            Timber.tag(loggerTag.value).i("Notification are disabled for this device")
            return false
        }

        if (vpnGateState.isClosed) {
            Timber.tag(loggerTag.value).i("VpnGate: gate closed, ignoring push")
            return false
        }

        val eventId = pushData.eventId
        val roomId = pushData.roomId
        if (eventId == null || roomId == null) {
            // A badge-only push: nothing to notify about, the counts ride along with the next sync.
            Timber.tag(loggerTag.value).d("Push carries no event, nothing to fetch")
            return false
        }

        if (isAppInForeground()) {
            // The running sync evaluates push rules itself, so there is nothing to fetch here.
            Timber.tag(loggerTag.value).d("PUSH received in a foreground state, ignore")
            return false
        }

        val sessionId = pushData.clientSecret?.let { unifiedPushStore.getSessionId(it) }
                ?: activeSessionHolder.getSafeActiveSession()?.sessionId
                        ?.takeIf { authenticationService.getAllSessionParams().size <= 1 }
        if (sessionId == null) {
            // Without a client secret and with several accounts signed in we cannot tell whose push
            // this is, and asking the wrong homeserver for the event would leak the other account's ids.
            Timber.tag(loggerTag.value).w("## Can't handle push, no session it can be attributed to")
            activeSessionHolder.getSafeActiveSession()?.syncService()?.requireBackgroundSync()
            return false
        }

        pushRequestStore.insertOrUpdate(
                PushRequest(
                        eventId = eventId,
                        roomId = roomId,
                        sessionId = sessionId,
                        pushDate = clock.epochMillis(),
                        providerInfo = providerInfo,
                )
        )
        FetchPendingPushWorker.enqueue(context, sessionId)
        return true
    }

    fun handleInvalid(providerInfo: String, data: String) {
        Timber.tag(loggerTag.value).w("Invalid received data Json format")
        coroutineScope.launch(Dispatchers.IO) {
            vectorDataStore.incrementPushCounter()
            pushRequestStore.insertOrUpdate(
                    PushRequest(
                            eventId = "",
                            roomId = "",
                            sessionId = activeSessionHolder.getSafeActiveSession()?.sessionId.orEmpty(),
                            pushDate = clock.epochMillis(),
                            providerInfo = providerInfo,
                            status = PushRequestStatus.FAILED,
                            failureReason = if (buildMeta.lowPrivacyLoggingEnabled) data else "invalid payload",
                    )
            )
        }
    }

    /**
     * Fetch the event a push pointed at and put it in the drawer.
     *
     * Failures are reported so the caller can tell a retryable error from a permanent one; when the
     * event cannot be resolved at all we still notify, because a placeholder the user can tap is
     * better than silence they read as a broken app.
     */
    suspend fun resolveAndNotify(session: Session, request: PushRequest): Result<Unit> {
        if (isEventAlreadyKnown(session, request)) {
            Timber.tag(loggerTag.value).d("Ignoring push, event already known")
            return Result.success(Unit)
        }
        return try {
            val event = session.eventService().getEvent(request.roomId, request.eventId)
            val resolvedEvent = notifiableEventResolver.resolveInMemoryEvent(session, event, canBeReplaced = true)
            if (resolvedEvent is NotifiableMessageEvent &&
                    notificationDrawerManager.shouldIgnoreMessageEventInRoom(resolvedEvent)) {
                return Result.success(Unit)
            }
            if (resolvedEvent == null) {
                notifyFallback(request)
            } else {
                DebugLog.i { "NOTIFDBG resolved event=${request.eventId} room=${request.roomId} type=${event.type}" }
                notificationDrawerManager.updateEvents { it.onNotifiableEventReceived(resolvedEvent) }
            }
            Result.success(Unit)
        } catch (throwable: Throwable) {
            Timber.tag(loggerTag.value).e(throwable, "Cannot resolve event ${request.eventId}")
            Result.failure(throwable)
        }
    }

    /** A push we could not turn into a message still deserves to be seen. */
    fun notifyFallback(request: PushRequest) {
        DebugLog.i { "NOTIFDBG fallback notification for event=${request.eventId} room=${request.roomId}" }
        notificationDrawerManager.updateEvents {
            it.onNotifiableEventReceived(
                    SimpleNotifiableEvent(
                            matrixID = request.sessionId,
                            eventId = request.eventId,
                            editedEventId = null,
                            noisy = false,
                            title = stringProvider.getString(CommonStrings.notification_fallback_title),
                            description = stringProvider.getString(CommonStrings.notification_fallback_content),
                            type = null,
                            timestamp = request.pushDate,
                            soundName = null,
                            canBeReplaced = true,
                    )
            )
        }
    }

    private suspend fun isAppInForeground(): Boolean = withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    private fun isEventAlreadyKnown(session: Session, request: PushRequest): Boolean {
        return tryOrNull {
            session.getRoom(request.roomId)?.getTimelineEvent(request.eventId) != null
        } ?: false
    }
}
