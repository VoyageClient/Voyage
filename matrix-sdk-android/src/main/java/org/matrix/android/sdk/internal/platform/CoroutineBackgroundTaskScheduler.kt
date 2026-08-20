/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.worker.BackgroundTaskBody
import org.matrix.android.sdk.internal.worker.BackgroundTaskContext
import org.matrix.android.sdk.internal.worker.BackgroundTaskOutcome
import org.matrix.android.sdk.internal.worker.SessionWorkerParams
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider

/**
 * Runs [BackgroundTaskBody] tasks as coroutines, for platforms without WorkManager. Unique queues
 * are serialized FIFO and a chain is simply two tasks in one queue, the first one's output params
 * feeding the second.
 *
 * Unlike WorkManager this keeps no state across process restarts: pending tasks are lost when the
 * process dies, and constraints (network) are not waited for — a task that needs the network fails
 * and is retried on the same backoff as any other failure.
 */
@SessionScope
internal class CoroutineBackgroundTaskScheduler @Inject constructor(
        private val scope: CoroutineScope,
        private val bodies: Map<BackgroundTaskType, @JvmSuppressWildcards Provider<BackgroundTaskBody<*>>>,
) : BackgroundTaskScheduler {

    private class Queue {
        var tail: Job? = null
        val jobs = mutableSetOf<Job>()
    }

    private val queues = mutableMapOf<String, Queue>()
    private val taggedJobs = mutableMapOf<String, MutableSet<Job>>()

    override fun enqueue(request: BackgroundTaskRequest<*>): BackgroundTaskHandle {
        val job = scope.launch { runWithRetries(request, request.params) }
        track(request, job)
        return handleOf(job)
    }

    override fun enqueueUnique(queueName: String, policy: BackgroundQueuePolicy, request: BackgroundTaskRequest<*>): BackgroundTaskHandle {
        return appendToQueue(queueName, policy, request, null)
    }

    override fun enqueueUniqueChain(
            queueName: String,
            policy: BackgroundQueuePolicy,
            first: BackgroundTaskRequest<*>,
            then: BackgroundTaskRequest<*>,
    ): BackgroundTaskHandle = appendToQueue(queueName, policy, first, then)

    private fun appendToQueue(
            queueName: String,
            policy: BackgroundQueuePolicy,
            first: BackgroundTaskRequest<*>,
            then: BackgroundTaskRequest<*>?,
    ): BackgroundTaskHandle {
        if (policy == BackgroundQueuePolicy.REPLACE) {
            cancelUniqueQueue(queueName)
        }
        val queue = synchronized(queues) { queues.getOrPut(queueName) { Queue() } }
        val previous = queue.tail
        val job = scope.launch {
            previous?.join()
            val output = runWithRetries(first, first.params)
            if (then != null) {
                // WorkManager merges the previous task's output into the next one's input, unless the
                // next one isolates it; that is how a failure reaches the tail of the chain.
                runWithRetries(then, if (then.isolateInput) then.params else output ?: then.params)
            }
        }
        queue.tail = job
        synchronized(queue.jobs) { queue.jobs.add(job) }
        job.invokeOnCompletion {
            synchronized(queue.jobs) { queue.jobs.remove(job) }
            synchronized(queues) { if (queue.tail === job) queues.remove(queueName) }
        }
        track(first, job)
        then?.let { track(it, job) }
        return handleOf(job)
    }

    override fun cancelUniqueQueue(queueName: String) {
        val queue = synchronized(queues) { queues.remove(queueName) } ?: return
        synchronized(queue.jobs) { queue.jobs.toList() }.forEach { it.cancel() }
    }

    override fun cancelAllByTag(tag: String) {
        val jobs = synchronized(taggedJobs) { taggedJobs.remove(tag) } ?: return
        jobs.toList().forEach { it.cancel() }
    }

    override fun cancelAllTasks() {
        synchronized(queues) { queues.values.toList().also { queues.clear() } }
                .forEach { queue -> synchronized(queue.jobs) { queue.jobs.toList() }.forEach { it.cancel() } }
        synchronized(taggedJobs) { taggedJobs.values.flatten().also { taggedJobs.clear() } }
                .forEach { it.cancel() }
    }

    private fun track(request: BackgroundTaskRequest<*>, job: Job) {
        if (request.extraTags.isEmpty()) return
        synchronized(taggedJobs) {
            request.extraTags.forEach { taggedJobs.getOrPut(it) { mutableSetOf() }.add(job) }
        }
        job.invokeOnCompletion {
            synchronized(taggedJobs) { request.extraTags.forEach { taggedJobs[it]?.remove(job) } }
        }
    }

    /** @return the params to hand to the next task of the chain, if any. */
    private suspend fun runWithRetries(request: BackgroundTaskRequest<*>, input: SessionWorkerParams): SessionWorkerParams? {
        val body = bodies[request.type]?.get()
        if (body == null) {
            Timber.e("No background task body for ${request.type}, dropping it")
            return null
        }
        if (request.initialDelayMillis > 0) {
            delay(request.initialDelayMillis)
        }
        val params = input
        var attempt = 0
        while (true) {
            val outcome = try {
                body.run(params, RunContext(attempt))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Timber.e(throwable, "Background task ${request.type} failed")
                return params.withFailure(throwable.localizedMessage ?: throwable::class.java.name)
            }
            when (outcome) {
                BackgroundTaskOutcome.Success -> return null
                is BackgroundTaskOutcome.SuccessWith -> return outcome.params
                BackgroundTaskOutcome.Failure -> return null
                BackgroundTaskOutcome.Retry -> {
                    attempt++
                    delay(RETRY_DELAY_MILLIS * attempt)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun BackgroundTaskBody<*>.run(params: SessionWorkerParams, context: RunContext) =
            (this as BackgroundTaskBody<SessionWorkerParams>).let {
                when (val failure = params.lastFailureMessage) {
                    null -> it.execute(params, context)
                    else -> it.onError(params, failure)
                }
            }

    private fun handleOf(job: Job) = BackgroundTaskHandle(UUID.randomUUID()) { job.cancel() }

    private class RunContext(override val attemptCount: Int) : BackgroundTaskContext {
        override val isStopped = false
    }

    companion object {
        private const val RETRY_DELAY_MILLIS = 10_000L
    }
}
