/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.task.Task
import timber.log.Timber
import javax.inject.Inject

internal interface FetchInvitedRoomPreviewTask : Task<FetchInvitedRoomPreviewTask.Params, Boolean> {
    data class Params(
            val roomId: String,
    )
}

/**
 * Seed the timeline of an invited (not yet joined) room with recent history, when the server
 * permits reading it — Synapse only does for world-readable rooms. An invited room has no chunks
 * (sync only delivers stripped state), so probe /messages from the live edge (`from` optional
 * since v1.3), anchor a live chunk at the returned position, and back-paginate into it through the
 * regular pipeline. Returns true when a preview was seeded; false when there is already local
 * history or the server refused.
 */
internal class DefaultFetchInvitedRoomPreviewTask @Inject constructor(
        private val roomAPI: RoomAPI,
        private val paginationTask: PaginationTask,
        private val globalErrorReceiver: GlobalErrorReceiver,
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val sessionDbDispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
) : FetchInvitedRoomPreviewTask {

    override suspend fun execute(params: FetchInvitedRoomPreviewTask.Params): Boolean {
        if (stores.chunk.lastForward(params.roomId) != null) return false
        val liveToken = try {
            executeRequest(globalErrorReceiver) {
                roomAPI.getRoomMessagesFrom(params.roomId, null, PaginationDirection.BACKWARDS.value, 1, null)
            }.start ?: return false
        } catch (failure: Throwable) {
            if (failure is Failure.ServerError && failure.error.code == MatrixError.M_FORBIDDEN) {
                Timber.d("Invited room ${params.roomId} is not previewable")
                return false
            }
            throw failure
        }
        val chunkId = database.awaitDbTransaction(sessionDbDispatcher) {
            stores.chunk.lastForward(params.roomId)?.id
                    ?: stores.chunk.insert(params.roomId, liveToken, null, null, null, isLastForward = true, isLastBackward = false, null, false)
        }
        paginationTask.execute(PaginationTask.Params(params.roomId, liveToken, PaginationDirection.BACKWARDS, PREVIEW_EVENT_COUNT, chunkId))
        return true
    }

    private companion object {
        private const val PREVIEW_EVENT_COUNT = 30
    }
}
