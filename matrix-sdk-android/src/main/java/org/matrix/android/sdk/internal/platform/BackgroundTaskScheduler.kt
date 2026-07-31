/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.platform

import org.matrix.android.sdk.api.util.Cancelable
import org.matrix.android.sdk.internal.worker.SessionWorkerParams
import java.util.UUID

/**
 * Platform seam for persistent background tasks, covering exactly the semantics the SDK uses from
 * WorkManager: fire-and-forget enqueue, named unique queues (serialized FIFO with append/replace),
 * one two-stage chain whose first task's output params feed the second, and cancellation by handle,
 * queue name, tag, or everything for the session. A desktop implementation can run these as
 * supervisor-scoped coroutines with params persisted for restart resume — it does not need
 * OS-scheduled deferral.
 */
internal interface BackgroundTaskScheduler {

    fun enqueue(request: BackgroundTaskRequest<*>): BackgroundTaskHandle

    fun enqueueUnique(queueName: String, policy: BackgroundQueuePolicy, request: BackgroundTaskRequest<*>): BackgroundTaskHandle

    /** The returned handle cancels the second task, matching the previous WorkManager behavior. */
    fun enqueueUniqueChain(
            queueName: String,
            policy: BackgroundQueuePolicy,
            first: BackgroundTaskRequest<*>,
            then: BackgroundTaskRequest<*>,
    ): BackgroundTaskHandle

    fun cancelUniqueQueue(queueName: String)

    fun cancelAllByTag(tag: String)

    /** Cancel every task scheduled by this session (used on session cleanup). */
    fun cancelAllTasks()
}

internal enum class BackgroundTaskType {
    ADD_PUSHER,
    UPDATE_TRUST,
    UPDATE_USER,
    SYNC,
    DEACTIVATE_LIVE_LOCATION,
    SEND_EVENT,
    UPLOAD_CONTENT,
    MULTIPLE_EVENT_DISPATCHER,
}

internal enum class BackgroundQueuePolicy {
    APPEND_OR_REPLACE,
    REPLACE,
}

/**
 * @param matrixConstraints apply the standard matrix constraints (network-connected, unless
 * disabled by WorkManagerConfig).
 * @param linearBackoff linear retry backoff at the minimum interval; false keeps the platform default.
 * @param isolateInput when appended to a queue or chain, do NOT merge the previous task's output
 * params into this task's input (WorkManager's NoMerger). With false, a previous task's error
 * params overwrite the input, which is how send-chain errors propagate.
 */
internal class BackgroundTaskRequest<P : SessionWorkerParams>(
        val type: BackgroundTaskType,
        val paramsClass: Class<P>,
        val params: P,
        val matrixConstraints: Boolean = false,
        val linearBackoff: Boolean = true,
        val initialDelayMillis: Long = 0,
        val isolateInput: Boolean = false,
        val extraTags: List<String> = emptyList(),
)

internal inline fun <reified P : SessionWorkerParams> backgroundTask(
        type: BackgroundTaskType,
        params: P,
        matrixConstraints: Boolean = false,
        linearBackoff: Boolean = true,
        initialDelayMillis: Long = 0,
        isolateInput: Boolean = false,
        extraTags: List<String> = emptyList(),
) = BackgroundTaskRequest(type, P::class.java, params, matrixConstraints, linearBackoff, initialDelayMillis, isolateInput, extraTags)

internal class BackgroundTaskHandle(
        val id: UUID,
        private val onCancel: () -> Unit,
) : Cancelable {

    override fun cancel() = onCancel()
}
