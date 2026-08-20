/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.aggregation.livelocation

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.worker.BackgroundTaskBody
import org.matrix.android.sdk.internal.worker.BackgroundTaskContext
import org.matrix.android.sdk.internal.worker.BackgroundTaskOutcome
import timber.log.Timber
import javax.inject.Inject

/**
 * Updates a live location summary so that it is considered as deactivated. For the context: it is
 * needed since a live location share should be deactivated after a certain timeout.
 */
internal class DeactivateLiveLocationShareTaskBody @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val sessionDbDispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
) : BackgroundTaskBody<DeactivateLiveLocationShareWorkerParams> {

    override suspend fun execute(params: DeactivateLiveLocationShareWorkerParams, context: BackgroundTaskContext): BackgroundTaskOutcome {
        return runCatching {
            deactivateLiveLocationShare(params)
        }.fold(
                onSuccess = {
                    BackgroundTaskOutcome.Success
                },
                onFailure = {
                    Timber.e("failed to deactivate live, eventId: ${params.eventId}, roomId: ${params.roomId}")
                    BackgroundTaskOutcome.Failure
                }
        )
    }

    private suspend fun deactivateLiveLocationShare(params: DeactivateLiveLocationShareWorkerParams) {
        database.awaitDbTransaction(sessionDbDispatcher) {
            Timber.d("deactivating live with id=${params.eventId}")
            stores.liveLocation.get(params.eventId)?.let {
                it.isActive = false
                stores.liveLocation.upsert(it)
            }
        }
    }
}
