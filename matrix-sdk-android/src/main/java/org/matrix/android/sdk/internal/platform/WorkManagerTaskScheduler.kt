/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.platform

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import org.matrix.android.sdk.internal.crypto.crosssigning.UpdateTrustWorker
import org.matrix.android.sdk.internal.di.WorkManagerProvider
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.content.UploadContentWorker
import org.matrix.android.sdk.internal.session.pushers.AddPusherWorker
import org.matrix.android.sdk.internal.session.room.aggregation.livelocation.DeactivateLiveLocationShareWorker
import org.matrix.android.sdk.internal.session.room.send.MultipleEventSendingDispatcherWorker
import org.matrix.android.sdk.internal.session.room.send.NoMerger
import org.matrix.android.sdk.internal.session.room.send.SendEventWorker
import org.matrix.android.sdk.internal.session.sync.handler.UpdateUserWorker
import org.matrix.android.sdk.internal.session.sync.job.SyncWorker
import org.matrix.android.sdk.internal.session.workmanager.WorkManagerConfig
import org.matrix.android.sdk.internal.worker.SessionWorkerParams
import org.matrix.android.sdk.internal.worker.WorkerParamsFactory
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@SessionScope
internal class WorkManagerTaskScheduler @Inject constructor(
        private val workManagerProvider: WorkManagerProvider,
        private val workManagerConfig: WorkManagerConfig,
) : BackgroundTaskScheduler {

    private val operationListenerExecutor = Executors.newSingleThreadExecutor()

    override fun enqueue(request: BackgroundTaskRequest<*>): BackgroundTaskHandle {
        val workRequest = request.toWorkRequest()
        workManagerProvider.workManager.enqueue(workRequest)
        return handleOf(workRequest)
    }

    override fun enqueueUnique(queueName: String, policy: BackgroundQueuePolicy, request: BackgroundTaskRequest<*>): BackgroundTaskHandle {
        val workRequest = request.toWorkRequest()
        workManagerProvider.workManager.enqueueUniqueWork(queueName, policy.toWorkPolicy(), workRequest)
        return handleOf(workRequest)
    }

    override fun enqueueUniqueChain(
            queueName: String,
            policy: BackgroundQueuePolicy,
            first: BackgroundTaskRequest<*>,
            then: BackgroundTaskRequest<*>,
    ): BackgroundTaskHandle {
        val firstRequest = first.toWorkRequest()
        val thenRequest = then.toWorkRequest()
        workManagerProvider.workManager
                .beginUniqueWork(queueName, policy.toWorkPolicy(), firstRequest)
                .then(thenRequest)
                .enqueue()
                .also { operation ->
                    operation.result.addListener(Runnable {
                        if (operation.result.isCancelled) {
                            Timber.e("CHAIN WAS CANCELLED")
                        } else if (operation.state.value is Operation.State.FAILURE) {
                            Timber.e("CHAIN DID FAIL")
                        }
                    }, operationListenerExecutor)
                }
        return handleOf(thenRequest)
    }

    override fun cancelUniqueQueue(queueName: String) {
        workManagerProvider.workManager.cancelUniqueWork(queueName)
    }

    override fun cancelAllByTag(tag: String) {
        workManagerProvider.workManager.cancelAllWorkByTag(tag)
    }

    override fun cancelAllTasks() {
        workManagerProvider.cancelAllWorks()
    }

    private fun handleOf(workRequest: OneTimeWorkRequest) = BackgroundTaskHandle(workRequest.id) {
        workManagerProvider.workManager.cancelWorkById(workRequest.id)
    }

    private fun BackgroundQueuePolicy.toWorkPolicy() = when (this) {
        BackgroundQueuePolicy.APPEND_OR_REPLACE -> ExistingWorkPolicy.APPEND_OR_REPLACE
        BackgroundQueuePolicy.REPLACE -> ExistingWorkPolicy.REPLACE
    }

    private fun BackgroundTaskRequest<*>.toWorkRequest(): OneTimeWorkRequest {
        return OneTimeWorkRequest.Builder(type.workerClass())
                .addTag(workManagerProvider.tag)
                .setInputData(toData())
                .apply {
                    if (matrixConstraints) {
                        setConstraints(WorkManagerProvider.getWorkConstraints(workManagerConfig))
                    }
                    if (linearBackoff) {
                        setBackoffCriteria(BackoffPolicy.LINEAR, WorkManagerProvider.BACKOFF_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                    }
                    if (initialDelayMillis > 0) {
                        setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
                    }
                    if (isolateInput) {
                        setInputMerger(NoMerger::class.java)
                    }
                    extraTags.forEach { addTag(it) }
                }
                .build()
    }

    private fun <P : SessionWorkerParams> BackgroundTaskRequest<P>.toData() =
            WorkerParamsFactory.toData(paramsClass, params)

    private fun BackgroundTaskType.workerClass(): Class<out ListenableWorker> = when (this) {
        BackgroundTaskType.ADD_PUSHER -> AddPusherWorker::class.java
        BackgroundTaskType.UPDATE_TRUST -> UpdateTrustWorker::class.java
        BackgroundTaskType.UPDATE_USER -> UpdateUserWorker::class.java
        BackgroundTaskType.SYNC -> SyncWorker::class.java
        BackgroundTaskType.DEACTIVATE_LIVE_LOCATION -> DeactivateLiveLocationShareWorker::class.java
        BackgroundTaskType.SEND_EVENT -> SendEventWorker::class.java
        BackgroundTaskType.UPLOAD_CONTENT -> UploadContentWorker::class.java
        BackgroundTaskType.MULTIPLE_EVENT_DISPATCHER -> MultipleEventSendingDispatcherWorker::class.java
    }
}
