/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.user.accountdata

import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.sync.FetchUnignoredContentTask
import org.matrix.android.sdk.internal.task.TaskExecutor
import javax.inject.Inject

/**
 * Runs [FetchUnignoredContentTask] for whoever [PendingUnIgnoreStore] is still holding. Draining the
 * store is what keeps the two callers — our own un-ignore, and every sync of this account — from
 * fetching the same thing twice; whichever gets there first takes the work.
 */
internal class UnIgnoredContentRecoverer @Inject constructor(
        @UserId private val userId: String,
        private val pendingUnIgnoreStore: PendingUnIgnoreStore,
        private val taskExecutor: TaskExecutor,
        // Lazy: the task reaches back here through SyncResponseHandler's post-treatment.
        private val fetchUnignoredContentTask: dagger.Lazy<FetchUnignoredContentTask>,
) {

    /**
     * Detached from the caller on purpose: this is a set of requests of its own, and both callers can
     * go away mid-flight — a screen closing, or a sync restarting when the app is foregrounded.
     */
    fun recoverPending() {
        val pending = pendingUnIgnoreStore.drain(userId)
        if (pending.isEmpty()) return
        taskExecutor.executorScope.launch {
            val recovered = tryOrNull("Unable to recover content after un-ignore") {
                fetchUnignoredContentTask.get().execute(FetchUnignoredContentTask.Params(pending.toList()))
            }
            // Failed (a released database, a dropped request): put them back for the next sync to retry.
            if (recovered == null) pendingUnIgnoreStore.add(userId, pending)
        }
    }
}
