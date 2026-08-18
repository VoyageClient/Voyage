/*
 * Copyright (c) 2021 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.sync.handler

import org.matrix.android.sdk.api.MatrixPatterns
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.internal.crypto.crosssigning.UpdateTrustWorker
import org.matrix.android.sdk.internal.crypto.crosssigning.UpdateTrustWorkerDataRepository
import org.matrix.android.sdk.internal.di.SessionId
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.platform.BackgroundQueuePolicy
import org.matrix.android.sdk.internal.platform.BackgroundTaskScheduler
import org.matrix.android.sdk.internal.platform.BackgroundTaskType
import org.matrix.android.sdk.internal.platform.backgroundTask
import org.matrix.android.sdk.internal.session.room.timeline.ReanchorRejoinedRoomTask
import org.matrix.android.sdk.internal.session.room.timeline.SeedJoinedRoomHistoryTask
import org.matrix.android.sdk.internal.session.sync.RoomSyncEphemeralTemporaryStore
import org.matrix.android.sdk.internal.session.sync.SyncResponsePostTreatmentAggregator
import org.matrix.android.sdk.internal.session.sync.model.accountdata.toMutable
import org.matrix.android.sdk.internal.session.user.accountdata.DirectChatsHelper
import org.matrix.android.sdk.internal.session.user.accountdata.PendingUnIgnoreStore
import org.matrix.android.sdk.internal.session.user.accountdata.UnIgnoredContentRecoverer
import org.matrix.android.sdk.internal.session.user.accountdata.UpdateUserAccountDataTask
import org.matrix.android.sdk.internal.util.logLimit
import timber.log.Timber
import javax.inject.Inject

internal class SyncResponsePostTreatmentAggregatorHandler @Inject constructor(
        private val directChatsHelper: DirectChatsHelper,
        private val ephemeralTemporaryStore: RoomSyncEphemeralTemporaryStore,
        private val updateUserAccountDataTask: UpdateUserAccountDataTask,
        private val updateTrustWorkerDataRepository: UpdateTrustWorkerDataRepository,
        private val backgroundTaskScheduler: BackgroundTaskScheduler,
        private val roomShieldSummaryUpdater: ShieldSummaryUpdater,
        private val reanchorRejoinedRoomTask: dagger.Lazy<ReanchorRejoinedRoomTask>,
        private val seedJoinedRoomHistoryTask: dagger.Lazy<SeedJoinedRoomHistoryTask>,
        private val pendingUnIgnoreStore: PendingUnIgnoreStore,
        private val unIgnoredContentRecoverer: UnIgnoredContentRecoverer,
        @UserId private val userId: String,
        @SessionId private val sessionId: String,
) {
    suspend fun handle(aggregator: SyncResponsePostTreatmentAggregator) {
        cleanupEphemeralFiles(aggregator.ephemeralFilesToDelete)
        updateDirectUserIds(aggregator.directChatsToCheck)
        fetchAndUpdateUsers(aggregator.userIdsToFetch)
        handleRefreshRoomShieldsForRooms(aggregator.roomsWithMembershipChangesForShieldUpdate)
        fetchUnignoredContentIfNeeded(aggregator.unIgnoredUserIds)
        reanchorRejoinedRooms(aggregator.rejoinedRoomsToReanchor)
        seedNewlyJoinedRooms(aggregator.newlyJoinedRooms)
    }

    /** Only rooms which really came in empty do anything here; the task checks before it asks the server. */
    private suspend fun seedNewlyJoinedRooms(roomIds: Set<String>) {
        roomIds.forEach { roomId ->
            tryOrNull("Failed to seed the history of newly joined room $roomId") {
                seedJoinedRoomHistoryTask.get().execute(SeedJoinedRoomHistoryTask.Params(roomId))
            }
        }
    }

    private suspend fun reanchorRejoinedRooms(roomIds: Set<String>) {
        roomIds.forEach { roomId ->
            tryOrNull("Failed to re-anchor rejoined room $roomId") {
                reanchorRejoinedRoomTask.get().execute(ReanchorRejoinedRoomTask.Params(roomId))
            }
        }
    }

    /**
     * Recovers what the homeserver withheld from users just un-ignored — those this sync brought news
     * of, plus anything an earlier attempt left pending. Every sync is a retry, which is what gets the
     * content in when the un-ignore was made through a session that has since been released.
     */
    private fun fetchUnignoredContentIfNeeded(unIgnoredUserIds: Set<String>) {
        pendingUnIgnoreStore.add(userId, unIgnoredUserIds)
        unIgnoredContentRecoverer.recoverPending()
    }

    private fun cleanupEphemeralFiles(ephemeralFilesToDelete: List<String>) {
        ephemeralFilesToDelete.forEach {
            ephemeralTemporaryStore.delete(it)
        }
    }

    private suspend fun updateDirectUserIds(directUserIdsToUpdate: Map<String, String>) {
        val directChats = directChatsHelper.getLocalDirectMessages().toMutable()
        var hasUpdate = false
        directUserIdsToUpdate.forEach { (roomId, candidateUserId) ->
            // consider room is a DM if referenced in the DM dictionary
            val currentDirectUserId = directChats.firstNotNullOfOrNull { (userId, roomIds) -> userId.takeIf { roomId in roomIds } }
            // update directUserId with the given candidateUserId if it mismatches the current one
            if (currentDirectUserId != null && !MatrixPatterns.isUserId(currentDirectUserId)) {
                // link roomId with the matrix id
                directChats
                        .getOrPut(candidateUserId) { arrayListOf() }
                        .apply {
                            if (!contains(roomId)) {
                                hasUpdate = true
                                add(roomId)
                            }
                        }

                // remove roomId from currentDirectUserId entry
                hasUpdate = hasUpdate or (directChats[currentDirectUserId]?.remove(roomId) == true)
                // remove currentDirectUserId entry if there is no attached room anymore
                hasUpdate = hasUpdate or (directChats.takeIf { it[currentDirectUserId].isNullOrEmpty() }?.remove(currentDirectUserId) != null)
            }
        }
        if (hasUpdate) {
            tryOrNull("Unable to update user account data") {
                updateUserAccountDataTask.execute(UpdateUserAccountDataTask.DirectChatParams(directMessages = directChats))
            }
        }
    }

    private fun fetchAndUpdateUsers(userIdsToFetch: Collection<String>) {
        if (userIdsToFetch.isEmpty()) return
        Timber.d("## Configure Worker to update users: ${userIdsToFetch.logLimit()}")
        val workerParams = UpdateTrustWorker.Params(
                sessionId = sessionId,
                filename = updateTrustWorkerDataRepository.createParam(userIdsToFetch.toList())
        )
        backgroundTaskScheduler.enqueueUnique(
                "USER_UPDATE_QUEUE_$sessionId",
                BackgroundQueuePolicy.APPEND_OR_REPLACE,
                backgroundTask(BackgroundTaskType.UPDATE_USER, workerParams)
        )
    }

    private fun handleRefreshRoomShieldsForRooms(roomIds: Set<String>) {
        if (roomIds.isEmpty()) return
        roomShieldSummaryUpdater.refreshShieldsForRoomIds(roomIds)
    }
}
