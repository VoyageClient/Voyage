/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.dehydration

import dagger.Lazy
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.SessionLifecycleObserver
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.task.TaskExecutor
import timber.log.Timber
import javax.inject.Inject

private val loggerTag = LoggerTag("DehydratedDeviceLoader", LoggerTag.CRYPTO)

/**
 * Picks up whatever the dehydrated device received while we were gone, and leaves a usable one
 * behind. Silently does nothing when secret storage is still locked: the key lives in there, and
 * the user may never unlock it this run.
 */
@SessionScope
internal class DehydratedDeviceLoader @Inject constructor(
        private val taskExecutor: TaskExecutor,
        private val dehydratedDeviceService: Lazy<DefaultDehydratedDeviceService>,
) : SessionLifecycleObserver {

    override fun onSessionStarted(session: Session) {
        taskExecutor.executorScope.launch {
            try {
                val result = dehydratedDeviceService.get().startDehydration()
                Timber.tag(loggerTag.value).d("Dehydration finished: $result")
            } catch (failure: Throwable) {
                Timber.tag(loggerTag.value).w(failure, "Dehydration failed")
            }
        }
    }
}
