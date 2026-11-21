/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.room.send.queue

import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.debug.DiagnosticContext
import org.matrix.android.sdk.api.util.Cancelable
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * @property queueIdentifier String value to identify a unique Queue
 * @property taskIdentifier String value to identify a unique Task. Should be different from queueIdentifier
 */
internal abstract class QueuedTask(
        val queueIdentifier: String,
        val taskIdentifier: String
) : Cancelable {

    override fun toString() = "${javaClass.simpleName} queueIdentifier: $queueIdentifier, taskIdentifier:  $taskIdentifier)"

    var retryCount = AtomicInteger(0)

    var rateLimitRetryCount = AtomicInteger(0)

    private var hasBeenCancelled: Boolean = false
    val diagnosticOrigin = DiagnosticContext.newOrigin("send.${javaClass.simpleName}")
    private val diagnosticEnqueuedAt = if (DebugLog.enabled) System.nanoTime() else 0L

    suspend fun execute() {
        if (DebugLog.enabled) withContext(DiagnosticContext.context(diagnosticOrigin)) { executeLogged() } else executeLogged()
    }

    private suspend fun executeLogged() {
        if (!isCancelled()) {
            val startedAt = if (DebugLog.enabled) System.nanoTime() else 0L
            DebugLog.i {
                "SENDDBG start task=${taskIdentifier.hashCode()} type=${javaClass.simpleName} retry=${retryCount.get()} rateLimitRetry=${rateLimitRetryCount.get()} " +
                        "sinceQueuedMs=${if (diagnosticEnqueuedAt != 0L) (System.nanoTime() - diagnosticEnqueuedAt) / 1_000_000 else null} ${DiagnosticContext.describe()}"
            }
            Timber.v("Execute: $this start")
            try {
                doExecute()
                DebugLog.i { "SENDDBG succeeded task=${taskIdentifier.hashCode()} ${DiagnosticContext.describe()}" }
            } catch (failure: Throwable) {
                DebugLog.w { "SENDDBG failed task=${taskIdentifier.hashCode()} error=${failure.javaClass.simpleName} ${DiagnosticContext.describe()}" }
                throw failure
            } finally {
                DebugLog.i { "SENDDBG execution-ended task=${taskIdentifier.hashCode()} elapsedMs=${if (startedAt != 0L) (System.nanoTime() - startedAt) / 1_000_000 else null} ${DiagnosticContext.describe()}" }
            }
            Timber.v("Execute: $this finish")
        } else DebugLog.i { "SENDDBG skipped-canceled task=${taskIdentifier.hashCode()} ${DiagnosticContext.describe()}" }
    }

    abstract suspend fun doExecute()

    abstract fun onTaskFailed()

    open fun isCancelled() = hasBeenCancelled

    final override fun cancel() {
        hasBeenCancelled = true
    }
}
