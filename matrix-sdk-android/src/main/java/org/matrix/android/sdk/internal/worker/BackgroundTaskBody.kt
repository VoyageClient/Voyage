/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.worker

import timber.log.Timber

/**
 * The work a background task actually does, independent of what schedules it: the android
 * [SessionSafeCoroutineWorker] shells run these through WorkManager, a desktop scheduler runs the
 * same bodies as coroutines.
 */
internal interface BackgroundTaskBody<PARAM : SessionWorkerParams> {

    suspend fun execute(params: PARAM, context: BackgroundTaskContext): BackgroundTaskOutcome

    /**
     * Called instead of [execute] when a previous task of the same queue/chain failed. Only return
     * a failure outcome for tasks that are never appended to a queue, else the queue stays stuck.
     */
    fun onError(params: PARAM, failureMessage: String): BackgroundTaskOutcome {
        Timber.e("Work cancelled due to input error from parent: $failureMessage")
        return BackgroundTaskOutcome.SuccessWith(params)
    }
}

/** What the scheduler running a body can tell it about the current run. */
internal interface BackgroundTaskContext {

    /** The scheduler asked the task to stop; it will be run again later. */
    val isStopped: Boolean

    /** 0 on the first run, incremented on every retry. */
    val attemptCount: Int
}

internal sealed interface BackgroundTaskOutcome {

    object Success : BackgroundTaskOutcome

    /** Success, passing params on to the next task of the chain (this is how errors propagate). */
    data class SuccessWith(val params: SessionWorkerParams) : BackgroundTaskOutcome

    object Retry : BackgroundTaskOutcome

    object Failure : BackgroundTaskOutcome
}
