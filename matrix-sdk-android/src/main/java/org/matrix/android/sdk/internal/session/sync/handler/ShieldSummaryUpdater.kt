/*
 * Copyright 2023 The Matrix.org Foundation C.I.C.
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

import org.matrix.android.sdk.internal.crypto.crosssigning.UpdateTrustWorker
import org.matrix.android.sdk.internal.crypto.crosssigning.UpdateTrustWorkerDataRepository
import org.matrix.android.sdk.internal.di.SessionId
import org.matrix.android.sdk.internal.platform.BackgroundQueuePolicy
import org.matrix.android.sdk.internal.platform.BackgroundTaskScheduler
import org.matrix.android.sdk.internal.platform.BackgroundTaskType
import org.matrix.android.sdk.internal.platform.backgroundTask
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.util.logLimit
import timber.log.Timber
import javax.inject.Inject

@SessionScope
internal class ShieldSummaryUpdater @Inject constructor(
        @SessionId private val sessionId: String,
        private val backgroundTaskScheduler: BackgroundTaskScheduler,
        private val updateTrustWorkerDataRepository: UpdateTrustWorkerDataRepository,
) {

    fun refreshShieldsForRoomIds(roomIds: Set<String>) {
        Timber.d("## CrossSigning - checkAffectedRoomShields for roomIds: ${roomIds.logLimit()}")
        val workerParams = UpdateTrustWorker.Params(
                sessionId = sessionId,
                filename = updateTrustWorkerDataRepository.createParam(emptyList(), roomIds = roomIds.toList())
        )
        backgroundTaskScheduler.enqueueUnique(
                "TRUST_UPDATE_QUEUE_$sessionId",
                BackgroundQueuePolicy.APPEND_OR_REPLACE,
                backgroundTask(BackgroundTaskType.UPDATE_TRUST, workerParams)
        )
    }
}
