/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.notifications

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.WorkerThread
import im.vector.app.ActiveSessionDataSource
import im.vector.app.R
import im.vector.app.core.resources.BuildMeta
import im.vector.app.core.utils.FirstThrottler
import im.vector.app.core.utils.PerfTrace
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.session.coroutineScope
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import org.matrix.android.sdk.api.session.getUserOrDefault
import org.matrix.android.sdk.api.session.pushrules.NotificationReadReceipt
import org.matrix.android.sdk.api.session.room.read.ReadService
import org.matrix.android.sdk.api.util.toMatrixItem
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationDrawerManager @Inject constructor(
        context: Context,
        private val notificationDisplayer: NotificationDisplayer,
        private val vectorPreferences: VectorPreferences,
        private val activeSessionDataSource: ActiveSessionDataSource,
        private val notifiableEventProcessor: NotifiableEventProcessor,
        private val notificationRenderer: NotificationRenderer,
        private val notificationEventPersistence: NotificationEventPersistence,
        private val filteredEventDetector: FilteredEventDetector,
        private val buildMeta: BuildMeta,
) {

    private val handlerThread: HandlerThread = HandlerThread("NotificationDrawerManager", Thread.MIN_PRIORITY)
    private var backgroundHandler: Handler

    private val currentSession: Session?
        get() = activeSessionDataSource.currentValue?.orNull()

    // Persisted notifications require an initialized session.
    private val notificationState by lazy { createInitialNotificationState() }
    private val avatarSize = context.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.profile_avatar_size)
    private val changedReceipts = mutableSetOf<Pair<String, String>>()
    private var reconciledPersistedEvents = false
    private val accountGeneration = AtomicLong()
    private var currentRoomId: String? = null
    private var currentThreadId: String? = null
    private val firstThrottler = FirstThrottler(200)

    private var useCompleteNotificationFormat = vectorPreferences.useCompleteNotificationFormat()

    init {
        handlerThread.start()
        backgroundHandler = Handler(handlerThread.looper)
    }

    private fun createInitialNotificationState(): NotificationState {
        val renderedEvents = mutableListOf<ProcessedEvent<NotifiableEvent>>()
        val queuedEvents = notificationEventPersistence.loadEvents(factory = { rawEvents ->
            renderedEvents.addAll(rawEvents.map { ProcessedEvent(ProcessedEvent.Type.KEEP, it) })
            NotificationEventQueue(rawEvents.toMutableList(), seenEventIds = CircularCache.create(cacheSize = 1000))
        })
        return NotificationState(queuedEvents, renderedEvents)
    }

    fun NotificationEventQueue.onNotifiableEventReceived(notifiableEvent: NotifiableEvent) {
        if (!canShowNotifications()) {
            Timber.i("Notification are disabled for this device")
            return
        }
        if (buildMeta.lowPrivacyLoggingEnabled) {
            Timber.d("onNotifiableEventReceived(): $notifiableEvent")
        } else {
            Timber.d("onNotifiableEventReceived(): is push: ${notifiableEvent.canBeReplaced}")
        }

        if (filteredEventDetector.shouldBeIgnored(notifiableEvent)) {
            Timber.d("onNotifiableEventReceived(): ignore the event")
            return
        }

        add(notifiableEvent)
    }

    fun clearAllEvents() {
        updateEvents { it.clear() }
    }

    fun setCurrentRoom(roomId: String?) {
        val dispatcher = currentSession?.coroutineDispatchers?.io ?: return
        val scope = currentSession?.coroutineScope ?: return
        scope.launch(dispatcher) {
            updateEvents {
                val hasChanged = roomId != currentRoomId
                currentRoomId = roomId
                if (hasChanged && roomId != null) {
                    it.clearMessagesForRoom(roomId)
                }
            }
        }
    }

    fun setCurrentThread(threadId: String?) {
        val dispatcher = currentSession?.coroutineDispatchers?.io ?: return
        val scope = currentSession?.coroutineScope ?: return
        scope.launch(dispatcher) {
            updateEvents {
                val hasChanged = threadId != currentThreadId
                currentThreadId = threadId
                currentRoomId?.let { roomId ->
                    if (hasChanged && threadId != null) {
                        it.clearMessagesForThread(roomId, threadId)
                    }
                }
            }
        }
    }

    // Notifications and their actions belong to a single account.
    fun resetForAccountSwitch() {
        backgroundHandler.removeCallbacks(refreshRunnable)
        notificationState.updateQueuedEvents(this) { queuedEvents, _ ->
            accountGeneration.incrementAndGet()
            queuedEvents.reset()
            changedReceipts.clear()
            currentRoomId = null
            currentThreadId = null
        }
        backgroundHandler.post {
            notificationState.clearAndAddRenderedEvents(emptyList())
            reconciledPersistedEvents = false
            persistEvents()
            notificationDisplayer.cancelAllNotifications()
        }
    }

    fun notificationStyleChanged() {
        updateEvents {
            val newSettings = vectorPreferences.useCompleteNotificationFormat()
            if (newSettings != useCompleteNotificationFormat) {
                notificationDisplayer.cancelAllNotifications()
                useCompleteNotificationFormat = newSettings
            }
        }
    }

    fun canShowNotifications(): Boolean =
            vectorPreferences.areNotificationEnabledForDevice() && notificationDisplayer.canShowNotifications()

    fun onReadReceiptsChanged(receipts: List<NotificationReadReceipt>) {
        val affected = notificationState.updateQueuedEvents(this) { queuedEvents, _ ->
            val queuedRooms = queuedEvents.rawEvents().filterIsInstance<NotifiableMessageEvent>().mapTo(HashSet()) { it.roomId }
            receipts.filter { it.roomId in queuedRooms }.forEach {
                changedReceipts.add(it.roomId to (it.threadId ?: ReadService.THREAD_ID_MAIN))
            }
            changedReceipts.isNotEmpty()
        }
        if (affected) refreshNotificationDrawer()
    }

    fun updateEvents(action: NotificationDrawerManager.(NotificationEventQueue) -> Unit) {
        notificationState.updateQueuedEvents(this) { queuedEvents, _ ->
            action(queuedEvents)
            if (!canShowNotifications()) queuedEvents.clear()
        }
        refreshNotificationDrawer()
    }

    private val refreshRunnable = Runnable {
        try {
            refreshNotificationDrawerBg()
        } catch (throwable: Throwable) {
            // The session can be released while a refresh is queued.
            Timber.w(throwable, "refreshNotificationDrawerBg failure")
        }
    }

    private fun refreshNotificationDrawer() {
        val canHandle = firstThrottler.canHandle()
        Timber.v("refreshNotificationDrawer(), delay: ${canHandle.waitMillis()} ms")
        backgroundHandler.removeCallbacks(refreshRunnable)
        backgroundHandler.postDelayed(refreshRunnable, canHandle.waitMillis())
    }

    @WorkerThread
    private fun refreshNotificationDrawerBg() = PerfTrace.time("notif.drawer.refresh") {
        Timber.v("refreshNotificationDrawerBg()")
        if (!canShowNotifications()) {
            notificationState.updateQueuedEvents(this) { queuedEvents, _ ->
                queuedEvents.clear()
                changedReceipts.clear()
            }
            notificationState.clearAndAddRenderedEvents(emptyList())
            notificationDisplayer.cancelAllNotifications()
            persistEvents()
            return@time
        }
        val generation = accountGeneration.get()
        val (snapshot, rendered, receipts) = notificationState.updateQueuedEvents(this) { queuedEvents, renderedEvents ->
            Triple(queuedEvents.rawEvents().toList(), renderedEvents.toList(), changedReceipts.toSet()).also { changedReceipts.clear() }
        }
        val eventsToRender = notifiableEventProcessor.process(
                snapshot, currentRoomId, currentThreadId, rendered, receipts.takeIf { reconciledPersistedEvents }
        )
        val session = currentSession ?: return@time
        if (generation != accountGeneration.get()) return@time
        reconciledPersistedEvents = true
        val removed = eventsToRender.filter { it.type == ProcessedEvent.Type.REMOVE }.map { it.event }
        if (removed.isNotEmpty()) {
            notificationState.updateQueuedEvents(this) { queuedEvents, _ -> queuedEvents.removeInstances(removed) }
        }

        if (notificationState.hasAlreadyRendered(eventsToRender)) {
            Timber.d("Skipping notification update due to event list not changing")
        } else {
            renderEvents(session, eventsToRender)
            notificationState.clearAndAddRenderedEvents(eventsToRender.filter { it.type == ProcessedEvent.Type.KEEP })
            persistEvents()
        }
    }

    private fun persistEvents() {
        notificationState.queuedEvents { queuedEvents ->
            notificationEventPersistence.persistEvents(queuedEvents)
        }
    }

    private fun renderEvents(session: Session, eventsToRender: List<ProcessedEvent<NotifiableEvent>>) {
        val user = session.getUserOrDefault(session.myUserId)
        // myUserDisplayName cannot be empty else NotificationCompat.MessagingStyle() will crash
        val myUserDisplayName = user.toMatrixItem().getBestName()
        val myUserAvatarUrl = session.contentUrlResolver().resolveThumbnail(
                contentUrl = user.avatarUrl,
                width = avatarSize,
                height = avatarSize,
                method = ContentUrlResolver.ThumbnailMethod.SCALE
        )
        notificationRenderer.render(session.myUserId, myUserDisplayName, myUserAvatarUrl, useCompleteNotificationFormat, eventsToRender)
    }

    fun shouldIgnoreMessageEventInRoom(resolvedEvent: NotifiableMessageEvent): Boolean {
        return resolvedEvent.shouldIgnoreMessageEventInRoom(currentRoomId, currentThreadId)
    }

    companion object {
        const val SUMMARY_NOTIFICATION_ID = 0
        const val ROOM_MESSAGES_NOTIFICATION_ID = 1
        const val ROOM_EVENT_NOTIFICATION_ID = 2
        const val ROOM_INVITATION_NOTIFICATION_ID = 3
    }
}
