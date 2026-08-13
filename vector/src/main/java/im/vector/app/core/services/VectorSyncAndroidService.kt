/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.core.services

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.Worker
import androidx.work.WorkerParameters
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.singletonEntryPoint
import im.vector.app.core.extensions.startForegroundCompat
import im.vector.app.features.notifications.NotificationUtils
import im.vector.app.features.settings.BackgroundSyncMode
import im.vector.lib.core.utils.timer.Clock
import im.vector.lib.core.utils.timer.DefaultClock
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.Matrix
import org.matrix.android.sdk.api.session.sync.job.SyncAndroidService
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class VectorSyncAndroidService : SyncAndroidService() {

    companion object {

        fun newOneShotIntent(
                context: Context,
                sessionId: String
        ): Intent {
            return Intent(context, VectorSyncAndroidService::class.java).also {
                it.putExtra(EXTRA_SESSION_ID, sessionId)
                it.putExtra(EXTRA_TIMEOUT_SECONDS, 0)
                it.putExtra(EXTRA_PERIODIC, false)
            }
        }

        fun newPeriodicIntent(
                context: Context,
                sessionId: String,
                syncTimeoutSeconds: Int,
                syncDelaySeconds: Int,
                isNetworkBack: Boolean
        ): Intent {
            return Intent(context, VectorSyncAndroidService::class.java).also {
                it.putExtra(EXTRA_SESSION_ID, sessionId)
                it.putExtra(EXTRA_TIMEOUT_SECONDS, syncTimeoutSeconds)
                it.putExtra(EXTRA_PERIODIC, true)
                it.putExtra(EXTRA_DELAY_SECONDS, syncDelaySeconds)
                it.putExtra(EXTRA_NETWORK_BACK_RESTART, isNetworkBack)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, VectorSyncAndroidService::class.java).also {
                it.action = ACTION_STOP
            }
        }

        const val TAG_RESTART_WHEN_NETWORK_ON = "RestartWhenNetworkOn"
    }

    @Inject lateinit var notificationUtils: NotificationUtils
    @Inject lateinit var matrix: Matrix
    @Inject lateinit var clock: Clock

    override fun provideMatrix() = matrix

    override fun getDefaultSyncDelaySeconds() = BackgroundSyncMode.DEFAULT_SYNC_DELAY_SECONDS

    override fun getDefaultSyncTimeoutSeconds() = BackgroundSyncMode.DEFAULT_SYNC_TIMEOUT_SECONDS

    override fun onStart(isInitialSync: Boolean) {
        val notificationSubtitleRes = if (isInitialSync) {
            CommonStrings.notification_initial_sync
        } else {
            CommonStrings.notification_listening_for_notifications
        }
        val notification = notificationUtils.buildForegroundServiceNotification(notificationSubtitleRes, false)
        startForegroundCompat(NotificationUtils.NOTIFICATION_ID_FOREGROUND_SERVICE, notification)
    }

    override fun onRescheduleAsked(
            sessionId: String,
            syncTimeoutSeconds: Int,
            syncDelaySeconds: Int
    ) {
        rescheduleSyncService(
                sessionId = sessionId,
                syncTimeoutSeconds = syncTimeoutSeconds,
                syncDelaySeconds = syncDelaySeconds,
                isPeriodic = true,
                isNetworkBack = false,
                clock = clock
        )
    }

    override fun onNetworkError(
            sessionId: String,
            syncTimeoutSeconds: Int,
            syncDelaySeconds: Int,
            isPeriodic: Boolean
    ) {
        Timber.d("## Sync: A network error occurred during sync")
        if (applicationContext.singletonEntryPoint().vpnGateState().isClosed) {
            Timber.i("VpnGate: gate closed, not scheduling network-restore sync restart")
            return
        }
        val rescheduleSyncWorkRequest: WorkRequest =
                OneTimeWorkRequestBuilder<RestartWhenNetworkOn>()
                        .setInputData(RestartWhenNetworkOn.createInputData(sessionId, syncTimeoutSeconds, syncDelaySeconds, isPeriodic))
                        .addTag(TAG_RESTART_WHEN_NETWORK_ON)
                        .setConstraints(
                                Constraints.Builder()
                                        .setRequiredNetworkType(NetworkType.CONNECTED)
                                        .build()
                        )
                        .build()

        Timber.d("## Sync: Schedule a work to restart service when network will be on")
        WorkManager
                .getInstance(applicationContext)
                .enqueue(rescheduleSyncWorkRequest)
    }

    override fun onDestroy() {
        removeForegroundNotification()
        super.onDestroy()
    }

    private fun removeForegroundNotification() {
        val notificationManager = getSystemService<NotificationManager>()!!
        notificationManager.cancel(NotificationUtils.NOTIFICATION_ID_FOREGROUND_SERVICE)
    }

    // I do not move or rename this class, since I'm not sure about the side effect regarding the WorkManager
    class RestartWhenNetworkOn(
            appContext: Context,
            workerParams: WorkerParameters
    ) : Worker(appContext, workerParams) {

        override fun doWork(): Result {
            Timber.d("## Sync: RestartWhenNetworkOn.doWork()")
            if (applicationContext.singletonEntryPoint().vpnGateState().isClosed) {
                Timber.i("VpnGate: gate closed, dropping network-restore sync restart")
                return Result.success()
            }
            val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
            val syncTimeoutSeconds = inputData.getInt(KEY_SYNC_TIMEOUT_SECONDS, BackgroundSyncMode.DEFAULT_SYNC_TIMEOUT_SECONDS)
            val syncDelaySeconds = inputData.getInt(KEY_SYNC_DELAY_SECONDS, BackgroundSyncMode.DEFAULT_SYNC_DELAY_SECONDS)
            val isPeriodic = inputData.getBoolean(KEY_IS_PERIODIC, false)

            // Not sure how to inject a Clock here
            val clock = DefaultClock()
            applicationContext.rescheduleSyncService(
                    sessionId = sessionId,
                    syncTimeoutSeconds = syncTimeoutSeconds,
                    syncDelaySeconds = syncDelaySeconds,
                    isPeriodic = isPeriodic,
                    isNetworkBack = true,
                    clock = clock
            )
            // Indicate whether the work finished successfully with the Result
            return Result.success()
        }

        companion object {
            fun createInputData(
                    sessionId: String,
                    syncTimeoutSeconds: Int,
                    syncDelaySeconds: Int,
                    isPeriodic: Boolean
            ): Data {
                return Data.Builder()
                        .putString(KEY_SESSION_ID, sessionId)
                        .putInt(KEY_SYNC_TIMEOUT_SECONDS, syncTimeoutSeconds)
                        .putInt(KEY_SYNC_DELAY_SECONDS, syncDelaySeconds)
                        .putBoolean(KEY_IS_PERIODIC, isPeriodic)
                        .build()
            }

            private const val KEY_SESSION_ID = "sessionId"
            private const val KEY_SYNC_TIMEOUT_SECONDS = "timeout"
            private const val KEY_SYNC_DELAY_SECONDS = "delay"
            private const val KEY_IS_PERIODIC = "isPeriodic"
        }
    }
}

private fun Context.rescheduleSyncService(
        sessionId: String,
        syncTimeoutSeconds: Int,
        syncDelaySeconds: Int,
        isPeriodic: Boolean,
        isNetworkBack: Boolean,
        clock: Clock
) {
    Timber.d("## Sync: rescheduleSyncService")
    if (singletonEntryPoint().vpnGateState().isClosed) {
        Timber.i("VpnGate: gate closed, not rescheduling sync service")
        return
    }
    if (isNetworkBack || syncDelaySeconds == 0) {
        // Do not wait, do the sync now (more reactivity if network back is due to user action)
        val intent = if (isPeriodic) {
            VectorSyncAndroidService.newPeriodicIntent(
                    context = this,
                    sessionId = sessionId,
                    syncTimeoutSeconds = syncTimeoutSeconds,
                    syncDelaySeconds = syncDelaySeconds,
                    isNetworkBack = isNetworkBack
            )
        } else {
            VectorSyncAndroidService.newOneShotIntent(
                    context = this,
                    sessionId = sessionId
            )
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (ex: Throwable) {
            Timber.e(ex, "## Sync: Failed to restart service, fallback to alarm")
            AlarmSyncBroadcastReceiver.scheduleAlarm(this, sessionId, syncDelaySeconds, clock)
        }
    } else {
        // Go through the single broadcast alarm chain, so this never competes with the
        // alarm scheduled by BackgroundSyncStarter and permission checks apply at each cycle
        AlarmSyncBroadcastReceiver.scheduleAlarm(this, sessionId, syncDelaySeconds, clock)
    }
}
