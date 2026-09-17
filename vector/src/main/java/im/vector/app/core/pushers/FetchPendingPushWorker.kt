/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.pushers

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.lib.core.utils.timer.Clock
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.logger.LoggerTag
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

private val loggerTag = LoggerTag("Push", LoggerTag.SYNC)

/**
 * Turns queued pushes into notifications, and leaves anything it could not fetch queued so the next
 * run picks it up. WorkManager keeps the process alive and holds its own wakelock while this runs,
 * which is why the push foreground service can be released as soon as we start.
 */
class FetchPendingPushWorker(
        context: Context,
        params: WorkerParameters,
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun pushRequestStore(): PushRequestStore
        fun pushHandler(): VectorPushHandler
        fun activeSessionHolder(): ActiveSessionHolder
        fun foregroundServiceManager(): FetchPushForegroundServiceManager
        fun clock(): Clock
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        deps.foregroundServiceManager().stopAll()

        val sessionId = inputData.getString(SESSION_ID) ?: return Result.failure()
        val store = deps.pushRequestStore()
        val clock = deps.clock()

        store.removeOlderThan(clock.epochMillis() - HISTORY_RETENTION_MS)
        val requests = store.getPending(sessionId, clock.epochMillis() - PENDING_WINDOW_MS)
        if (requests.isEmpty()) {
            Timber.tag(loggerTag.value).d("No pending push to fetch")
            return Result.success()
        }

        val session = deps.activeSessionHolder().getSafeActiveSession()
                ?.takeIf { it.sessionId == sessionId }
        if (session == null) {
            // Another account is active: its own registration will bring these in when it returns.
            Timber.tag(loggerTag.value).w("Session $sessionId is not active, keeping ${requests.size} push(es) pending")
            return Result.success()
        }

        Timber.tag(loggerTag.value).d("Fetching ${requests.size} pending push(es)")
        val pushHandler = deps.pushHandler()
        val updated = requests.map { request ->
            pushHandler.resolveAndNotify(session, request).fold(
                    onSuccess = { request.copy(status = PushRequestStatus.SUCCESS, failureReason = null) },
                    onFailure = { throwable ->
                        if (throwable.isRetryable()) {
                            request.copy(
                                    status = PushRequestStatus.PENDING,
                                    retries = request.retries + 1,
                                    failureReason = throwable.localizedMessage,
                            )
                        } else {
                            // Nothing will make this event resolvable, so show the user something.
                            pushHandler.notifyFallback(request)
                            request.copy(status = PushRequestStatus.FAILED, failureReason = throwable.localizedMessage)
                        }
                    }
            )
        }
        store.insertOrUpdate(updated)

        // The event may need room state or keys the fetch alone does not carry.
        session.syncService().requireBackgroundSync()

        val stillPending = updated.any { it.status == PushRequestStatus.PENDING && it.retries < MAX_RETRIES }
        return if (stillPending) Result.retry() else Result.success()
    }

    private fun Throwable.isRetryable(): Boolean {
        return this is IOException ||
                this is Failure.NetworkConnection ||
                (this as? Failure.ServerError)?.httpCode in RETRYABLE_CODES ||
                (this as? Failure.OtherServerError)?.httpCode in RETRYABLE_CODES
    }

    companion object {
        const val SESSION_ID = "SESSION_ID"

        private const val MAX_RETRIES = 5
        private val RETRYABLE_CODES = listOf(429, 500, 502, 503, 504)
        private val PENDING_WINDOW_MS = TimeUnit.DAYS.toMillis(1)
        private val HISTORY_RETENTION_MS = TimeUnit.DAYS.toMillis(7)

        fun enqueue(context: Context, sessionId: String) {
            val request = OneTimeWorkRequestBuilder<FetchPendingPushWorker>()
                    .setInputData(Data.Builder().putString(SESSION_ID, sessionId).build())
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                    workName(sessionId),
                    // A run already in flight covers the push we just queued, but we must not lose one
                    // that lands while it is finishing up.
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request,
            )
        }

        private fun workName(sessionId: String) = "FETCH_PENDING_PUSH_$sessionId"
    }
}
