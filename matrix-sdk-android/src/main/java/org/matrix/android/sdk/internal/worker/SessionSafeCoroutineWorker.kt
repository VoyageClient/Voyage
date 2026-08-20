/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.internal.SessionManager
import org.matrix.android.sdk.internal.session.SessionComponent
import timber.log.Timber

/**
 * This worker should only sends Result.Success when added to a unique queue to avoid breaking the unique queue.
 * This abstract class handle the cases of problem when parsing parameter, and forward the error if any to
 * the next workers.
 *
 * The work itself lives in a [BackgroundTaskBody] so that it can also run off WorkManager.
 */
internal abstract class SessionSafeCoroutineWorker<PARAM : SessionWorkerParams>(
        context: Context,
        workerParameters: WorkerParameters,
        private val sessionManager: SessionManager,
        private val paramClass: Class<PARAM>
) : CoroutineWorker(context, workerParameters) {

    @JsonClass(generateAdapter = true)
    internal data class ErrorData(
            override val sessionId: String,
            override val lastFailureMessage: String? = null
    ) : SessionWorkerParams {

        override fun withFailure(message: String) = copy(lastFailureMessage = lastFailureMessage ?: message)
    }

    private val taskContext = object : BackgroundTaskContext {
        override val isStopped get() = this@SessionSafeCoroutineWorker.isStopped
        override val attemptCount get() = runAttemptCount
    }

    final override suspend fun doWork(): Result {
        val params = WorkerParamsFactory.fromData(paramClass, inputData)
                ?: return buildErrorResult(null, "Unable to parse work parameters")
                        .also { Timber.e("Unable to parse work parameters") }

        return try {
            val sessionComponent = sessionManager.getSessionComponent(params.sessionId)
                    ?: return buildErrorResult(params, "No session")

            // Make sure to inject before handling error as you may need some dependencies to process them.
            injectWith(sessionComponent)

            when (val lastFailureMessage = params.lastFailureMessage) {
                null -> body().execute(params, taskContext)
                else -> body().onError(params, lastFailureMessage)
            }.toResult()
        } catch (throwable: Throwable) {
            buildErrorResult(params, "${throwable::class.java.name}: ${throwable.localizedMessage ?: "N/A error message"}")
        }
    }

    abstract fun injectWith(injector: SessionComponent)

    abstract fun body(): BackgroundTaskBody<PARAM>

    private fun BackgroundTaskOutcome.toResult(): Result = when (this) {
        BackgroundTaskOutcome.Success -> Result.success()
        is BackgroundTaskOutcome.SuccessWith -> Result.success(params.toData())
        BackgroundTaskOutcome.Retry -> Result.retry()
        BackgroundTaskOutcome.Failure -> Result.failure()
    }

    private fun buildErrorResult(params: PARAM?, message: String): Result {
        return Result.success(
                if (params != null) {
                    params.withFailure(message).toData()
                } else {
                    WorkerParamsFactory.toData(ErrorData::class.java, ErrorData(sessionId = "", lastFailureMessage = message))
                }
        )
    }

    private fun SessionWorkerParams.toData() = WorkerParamsFactory.toData(javaClass, this)

    companion object {
        fun hasFailed(outputData: Data): Boolean {
            return WorkerParamsFactory.fromData(ErrorData::class.java, outputData)
                    .let { it?.lastFailureMessage != null }
        }
    }
}
