/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.pushrules.NotificationReadReceipt
import org.matrix.android.sdk.api.session.pushrules.PushEvents
import org.matrix.android.sdk.api.session.pushrules.PushRuleService
import org.matrix.android.sdk.api.session.pushrules.getActions
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PushRuleTriggerListener @Inject constructor(
        private val resolver: NotifiableEventResolver,
        private val notificationDrawerManager: NotificationDrawerManager
) : PushRuleService.PushRuleListener {

    private var session: Session? = null
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob())

    override fun onEvents(pushEvents: PushEvents) {
        val eventSession = session ?: return Timber.e("Called without active session")
        scope.launch {
            if (session !== eventSession) return@launch
            val notifiableEvents = if (notificationDrawerManager.canShowNotifications()) createNotifiableEvents(pushEvents, eventSession) else emptyList()
            if (session !== eventSession) return@launch
            notificationDrawerManager.updateEvents { queuedEvents ->
                notifiableEvents.forEach { queuedEvents.onNotifiableEventReceived(it) }
                queuedEvents.syncRoomEvents(roomsLeft = pushEvents.roomsLeft, roomsJoined = pushEvents.roomsJoined)
                queuedEvents.markRedacted(pushEvents.redactedEventIds)
            }
        }
    }

    private suspend fun createNotifiableEvents(pushEvents: PushEvents, session: Session): List<NotifiableEvent> {
        return pushEvents.matchedEvents.mapNotNull { (event, pushRule) ->
            Timber.d("Push rule match for event ${event.eventId}")
            val action = pushRule.getActions().toNotificationAction()
            if (action.shouldNotify) {
                val resolved = resolver.resolveEvent(event, session, isNoisy = !action.soundName.isNullOrBlank())
                DebugLog.i { "NOTIFDBG notify rule=${pushRule.ruleId} for ${session.myUserId} " +
                                "noisy=${!action.soundName.isNullOrBlank()} " +
                                "event=${event.eventId} room=${event.roomId} resolved=${resolved != null}" }
                resolved
            } else {
                DebugLog.i { "NOTIFDBG suppressed rule=${pushRule.ruleId} (dont_notify) event=${event.eventId} room=${event.roomId}" }
                null
            }
        }
    }

    fun startWithSession(session: Session) {
        if (this.session != null) {
            stop()
        }
        this.session = session
        session.pushRuleService().addPushRuleListener(this)
        notificationDrawerManager.updateEvents { }
        var previous = emptyMap<Pair<String, String?>, NotificationReadReceipt>()
        session.pushRuleService().getNotificationReadReceiptsFlow()
                .onEach { receipts ->
                    if (this.session === session) {
                        val current = receipts.associateBy { it.roomId to it.threadId }
                        notificationDrawerManager.onReadReceiptsChanged(receipts.filter { previous[it.roomId to it.threadId] != it })
                        previous = current
                    }
                }
                .launchIn(scope)
    }

    fun stop() {
        scope.coroutineContext.cancelChildren(CancellationException("PushRuleTriggerListener stopping"))
        session?.pushRuleService()?.removePushRuleListener(this)
        session = null
        notificationDrawerManager.resetForAccountSwitch()
    }
}
