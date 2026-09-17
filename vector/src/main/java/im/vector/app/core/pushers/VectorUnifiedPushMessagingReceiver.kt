/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.pushers

import android.content.Context
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.services.GuardServiceStarter
import im.vector.app.features.settings.BackgroundSyncMode
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.matrix.android.sdk.api.logger.LoggerTag
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import timber.log.Timber
import javax.inject.Inject

private val loggerTag = LoggerTag("Push", LoggerTag.SYNC)

/**
 * Hilt injection happen at super.onReceive().
 */
@AndroidEntryPoint
class VectorUnifiedPushMessagingReceiver : MessagingReceiver() {
    @Inject lateinit var pushersManager: PushersManager
    @Inject lateinit var pushParser: PushParser
    @Inject lateinit var activeSessionHolder: ActiveSessionHolder
    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var vectorPushHandler: VectorPushHandler
    @Inject lateinit var guardServiceStarter: GuardServiceStarter
    @Inject lateinit var unifiedPushStore: UnifiedPushStore
    @Inject lateinit var unifiedPushHelper: UnifiedPushHelper
    @Inject lateinit var foregroundServiceManager: FetchPushForegroundServiceManager
    @Inject lateinit var pushHealthCheckScheduler: PushHealthCheckScheduler

    private val coroutineScope = CoroutineScope(SupervisorJob())

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        Timber.tag(loggerTag.value).d("New message, decrypted: ${message.decrypted}")
        // Hold a wakelock: once onReceive returns this process is killable and a dozing device will
        // never run the fetch, which is how a push turns into a notification that never arrives.
        foregroundServiceManager.start()
        coroutineScope.launch {
            var queued = false
            try {
                val pushData = pushParser.parsePushDataUnifiedPush(message.content, instance)
                if (pushData == null) {
                    vectorPushHandler.handleInvalid(providerInfo(instance), String(message.content))
                } else {
                    queued = vectorPushHandler.handle(pushData, providerInfo(instance))
                }
            } finally {
                // The worker holds its own wakelock from here on.
                if (!queued) foregroundServiceManager.stop()
            }
        }
    }

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        Timber.tag(loggerTag.value).i("onNewEndpoint: adding ${endpoint.url}")
        if (vectorPreferences.areNotificationEnabledForDevice() && activeSessionHolder.hasActiveSession()) {
            coroutineScope.launch {
                val gateway = unifiedPushHelper.storeGatewayForEndpoint(instance, endpoint.url)
                // Store the endpoint only once the homeserver has taken the pusher: a distributor
                // hands back the same endpoint forever, so an endpoint saved after a failed
                // registration would look up to date while no pusher exists, and never be retried.
                pushersManager.registerPusher(endpoint.url, gateway)
                        .onSuccess {
                            unifiedPushStore.storeUpEndpoint(instance, endpoint.url)
                            pushHealthCheckScheduler.schedule()
                        }
                        .onFailure { Timber.tag(loggerTag.value).e(it, "Failed to register the pusher, will retry") }
            }
        }
        val mode = BackgroundSyncMode.FDROID_BACKGROUND_SYNC_MODE_DISABLED
        vectorPreferences.setFdroidSyncBackgroundMode(mode)
        guardServiceStarter.stop()
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        Timber.tag(loggerTag.value).e("onRegistrationFailed for $instance, reason: $reason")
        Toast.makeText(context, "Push service registration failed", Toast.LENGTH_SHORT).show()
        fallBackToBackgroundSync()
    }

    override fun onUnregistered(context: Context, instance: String) {
        Timber.tag(loggerTag.value).d("Unifiedpush: Unregistered")
        fallBackToBackgroundSync()
        runBlocking {
            try {
                unifiedPushStore.getEndpoint(instance)?.let { pushersManager.unregisterPusher(it) }
            } catch (e: Exception) {
                Timber.tag(loggerTag.value).d("Probably unregistering a non existing pusher")
            }
            unifiedPushStore.forgetInstance(instance)
        }
    }

    private fun fallBackToBackgroundSync() {
        vectorPreferences.setFdroidSyncBackgroundMode(BackgroundSyncMode.FDROID_BACKGROUND_SYNC_MODE_FOR_REALTIME)
        guardServiceStarter.start()
    }

    private fun providerInfo(instance: String) = "UnifiedPush - ${unifiedPushHelper.getCurrentDistributorName()} - $instance"
}
