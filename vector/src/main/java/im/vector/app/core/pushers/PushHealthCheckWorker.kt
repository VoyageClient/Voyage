/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.pushers

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.core.utils.timer.Clock
import org.matrix.android.sdk.api.logger.LoggerTag
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private val loggerTag = LoggerTag("Push", LoggerTag.SYNC)

/**
 * Re-registers the pusher on a schedule.
 *
 * A pusher is not permanent: a homeserver drops one whose gateway reports the pushkey as rejected,
 * and backs off from one that keeps failing. Both leave the device quietly unreachable, with the
 * endpoint still valid so nothing on this side notices. Re-posting it costs one request and puts
 * delivery back without the user having to open the app.
 */
class PushHealthCheckWorker(
        context: Context,
        params: WorkerParameters,
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun unifiedPushHelper(): UnifiedPushHelper
        fun pushersManager(): PushersManager
        fun vectorPreferences(): VectorPreferences
        fun activeSessionHolder(): ActiveSessionHolder
        fun pushRequestStore(): PushRequestStore
        fun clock(): Clock
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        if (!deps.vectorPreferences().areNotificationEnabledForDevice()) return Result.success()

        val helper = deps.unifiedPushHelper()
        if (helper.isBackgroundSync()) return Result.success()
        if (deps.activeSessionHolder().getSafeActiveSession() == null) return Result.success()

        val endpoint = helper.getEndpointOrToken() ?: return Result.success()
        val gateway = helper.getPushGateway() ?: return Result.success()

        catchUpIfNothingArrived(deps)

        return deps.pushersManager().registerPusher(endpoint, gateway).fold(
                onSuccess = {
                    Timber.tag(loggerTag.value).d("Push health check: pusher refreshed")
                    Result.success()
                },
                onFailure = {
                    Timber.tag(loggerTag.value).w(it, "Push health check: could not refresh the pusher")
                    Result.retry()
                }
        )
    }

    /**
     * When push has gone quiet for longer than a check interval it may be dead rather than idle, so
     * sync once to catch up. A device that is receiving pushes normally never reaches this.
     */
    private suspend fun catchUpIfNothingArrived(deps: Dependencies) {
        val lastPush = deps.pushRequestStore().getRecent(1).firstOrNull()?.pushDate ?: 0L
        if (deps.clock().epochMillis() - lastPush < TimeUnit.HOURS.toMillis(INTERVAL_HOURS)) return
        Timber.tag(loggerTag.value).d("Push health check: no push for a while, syncing")
        deps.activeSessionHolder().getSafeActiveSession()?.syncService()?.requireBackgroundSync()
    }

    companion object {
        private const val WORK_NAME = "PUSH_HEALTH_CHECK"
        const val INTERVAL_HOURS = 4L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PushHealthCheckWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

/** Indirection so the scheduling can be faked: WorkManager is not available to unit tests. */
class PushHealthCheckScheduler @Inject constructor(
        @ApplicationContext private val context: Context,
) {
    fun schedule() = PushHealthCheckWorker.schedule(context)

    fun cancel() = PushHealthCheckWorker.cancel(context)
}
