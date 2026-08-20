/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.job

import org.matrix.android.sdk.internal.platform.BackgroundQueuePolicy
import org.matrix.android.sdk.internal.platform.BackgroundTaskScheduler
import org.matrix.android.sdk.internal.platform.BackgroundTaskType
import org.matrix.android.sdk.internal.platform.backgroundTask
import java.util.concurrent.TimeUnit

internal object BackgroundSyncScheduling {

    private const val BG_SYNC_WORK_NAME = "BG_SYNCP"

    // WorkManager unique names are process-global while the scheduler is per-session:
    // without the sessionId, one account's background sync replaces/cancels the other's.
    private fun workName(sessionId: String) = "${BG_SYNC_WORK_NAME}_$sessionId"

    fun requireBackgroundSync(
            backgroundTaskScheduler: BackgroundTaskScheduler,
            sessionId: String,
            serverTimeoutInSeconds: Long = 0
    ) {
        val params = SyncWorkerParams(
                sessionId = sessionId,
                timeout = serverTimeoutInSeconds,
                delay = 0L,
                periodic = false
        )
        backgroundTaskScheduler.enqueueUnique(
                workName(sessionId),
                BackgroundQueuePolicy.APPEND_OR_REPLACE,
                backgroundTask(BackgroundTaskType.SYNC, params, matrixConstraints = true, isolateInput = true)
        )
    }

    fun automaticallyBackgroundSync(
            backgroundTaskScheduler: BackgroundTaskScheduler,
            sessionId: String,
            serverTimeoutInSeconds: Long = 0,
            delayInSeconds: Long = 30,
            forceImmediate: Boolean = false
    ) {
        val params = SyncWorkerParams(
                sessionId = sessionId,
                timeout = serverTimeoutInSeconds,
                delay = delayInSeconds,
                periodic = true,
                forceImmediate = forceImmediate
        )
        // Avoid risking multiple chains of syncs by replacing the existing chain
        backgroundTaskScheduler.enqueueUnique(
                workName(sessionId),
                BackgroundQueuePolicy.REPLACE,
                backgroundTask(
                        BackgroundTaskType.SYNC,
                        params,
                        matrixConstraints = true,
                        initialDelayMillis = if (forceImmediate) 0 else TimeUnit.SECONDS.toMillis(delayInSeconds),
                )
        )
    }

    fun stopAnyBackgroundSync(backgroundTaskScheduler: BackgroundTaskScheduler, sessionId: String) {
        backgroundTaskScheduler.cancelUniqueQueue(workName(sessionId))
        // Chains enqueued before the names became session-suffixed.
        backgroundTaskScheduler.cancelUniqueQueue(BG_SYNC_WORK_NAME)
    }
}
