/*
 * Copyright 2022 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.room.aggregation.livelocation

import org.matrix.android.sdk.api.extensions.orTrue
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.MessageBeaconInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageBeaconLocationDataContent
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.model.livelocation.LiveLocationShareAggregatedSummaryEntity
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.SessionId
import org.matrix.android.sdk.internal.platform.BackgroundQueuePolicy
import org.matrix.android.sdk.internal.platform.BackgroundTaskScheduler
import org.matrix.android.sdk.internal.platform.BackgroundTaskType
import org.matrix.android.sdk.internal.platform.backgroundTask
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber
import javax.inject.Inject

/**
 * Aggregates all live location sharing related events in local database.
 */
internal class LiveLocationAggregationProcessor @Inject constructor(
        @SessionId private val sessionId: String,
        private val backgroundTaskScheduler: BackgroundTaskScheduler,
        private val clock: Clock,
) {

    /**
     * Handle the content of a beacon info.
     * @return true if it has been processed, false if ignored.
     */
    fun handleBeaconInfo(stores: SessionStores, event: Event, content: MessageBeaconInfoContent, roomId: String, isLocalEcho: Boolean): Boolean {
        val senderId = event.senderId
        if (senderId.isNullOrEmpty() || isLocalEcho) {
            return false
        }
        val eventId = event.eventId

        val isLive = content.isLive.orTrue()
        val targetEventId = if (isLive) {
            eventId
        } else {
            // when live is set to false, we use the id of the event that should have been replaced
            event.unsignedData?.replacesState
        }

        if (targetEventId.isNullOrEmpty()) {
            Timber.w("no target event id found for the beacon content")
            return false
        }

        val aggregatedSummary = stores.liveLocation.get(targetEventId)
                ?: LiveLocationShareAggregatedSummaryEntity(eventId = targetEventId, roomId = roomId)

        if (!isLive && !eventId.isNullOrEmpty()) {
            // in this case, the received event is a new state event related to the previous one
            addRelatedEventId(eventId, aggregatedSummary)
        }

        // remote event can stay with isLive == true while the local summary is no more active
        val isActive = aggregatedSummary.isActive.orTrue() && isLive
        val endOfLiveTimestampMillis = content.getBestTimestampMillis()?.let { it + (content.timeout ?: 0) }
        Timber.d("updating summary of id=$targetEventId with isActive=$isActive and endTimestamp=$endOfLiveTimestampMillis")

        aggregatedSummary.startOfLiveTimestampMillis = content.getBestTimestampMillis()
        aggregatedSummary.endOfLiveTimestampMillis = endOfLiveTimestampMillis
        aggregatedSummary.isActive = isActive
        aggregatedSummary.userId = senderId
        stores.liveLocation.upsert(aggregatedSummary)

        deactivateAllPreviousBeacons(stores, roomId, senderId, targetEventId, content.getBestTimestampMillis() ?: 0)

        if (isActive) {
            scheduleDeactivationAfterTimeout(targetEventId, roomId, endOfLiveTimestampMillis)
        } else {
            cancelDeactivationAfterTimeout(targetEventId, roomId)
        }

        return true
    }

    private fun scheduleDeactivationAfterTimeout(eventId: String, roomId: String, endOfLiveTimestampMillis: Long?) {
        endOfLiveTimestampMillis ?: return

        val workParams = DeactivateLiveLocationShareWorker.Params(sessionId = sessionId, eventId = eventId, roomId = roomId)
        val workName = DeactivateLiveLocationShareWorker.getWorkName(eventId = eventId, roomId = roomId)
        val workDelayMillis = (endOfLiveTimestampMillis - clock.epochMillis()).coerceAtLeast(0)
        Timber.d("scheduling deactivation of $eventId after $workDelayMillis millis")
        backgroundTaskScheduler.enqueueUnique(
                workName,
                BackgroundQueuePolicy.REPLACE,
                backgroundTask(
                        BackgroundTaskType.DEACTIVATE_LIVE_LOCATION,
                        workParams,
                        linearBackoff = false,
                        initialDelayMillis = workDelayMillis,
                )
        )
    }

    private fun cancelDeactivationAfterTimeout(eventId: String, roomId: String) {
        val workName = DeactivateLiveLocationShareWorker.getWorkName(eventId = eventId, roomId = roomId)
        backgroundTaskScheduler.cancelUniqueQueue(workName)
    }

    /**
     * Handle the content of a beacon location data.
     * @return true if it has been processed, false if ignored.
     */
    fun handleBeaconLocationData(
            stores: SessionStores,
            event: Event,
            content: MessageBeaconLocationDataContent,
            roomId: String,
            relatedEventId: String?,
            isLocalEcho: Boolean
    ): Boolean {
        if (event.senderId.isNullOrEmpty() || isLocalEcho) {
            return false
        }

        if (relatedEventId.isNullOrEmpty()) {
            Timber.w("no related event id found for the live location content")
            return false
        }

        val aggregatedSummary = stores.liveLocation.get(relatedEventId)
                ?: LiveLocationShareAggregatedSummaryEntity(eventId = relatedEventId, roomId = roomId)

        val eventId = event.eventId
        if (!eventId.isNullOrEmpty()) {
            addRelatedEventId(eventId, aggregatedSummary)
        }

        val updatedLocationTimestamp = content.getBestTimestampMillis() ?: 0
        val currentLocationTimestamp = ContentMapper
                .map(aggregatedSummary.lastLocationContent)
                .toModel<MessageBeaconLocationDataContent>()
                ?.getBestTimestampMillis()
                ?: 0

        val updated = updatedLocationTimestamp.isMoreRecentThan(currentLocationTimestamp)
        if (updated) {
            Timber.d("updating last location of the summary of id=$relatedEventId")
            aggregatedSummary.lastLocationContent = ContentMapper.map(content.toContent())
        }
        stores.liveLocation.upsert(aggregatedSummary)
        return updated
    }

    private fun addRelatedEventId(
            eventId: String,
            aggregatedSummary: LiveLocationShareAggregatedSummaryEntity
    ) {
        Timber.d("adding related event id $eventId to summary of id ${aggregatedSummary.eventId}")
        val updatedEventIds = aggregatedSummary.relatedEventIds.toMutableList().also {
            it.add(eventId)
        }
        aggregatedSummary.relatedEventIds = mutableListOf(*updatedEventIds.toTypedArray())
    }

    private fun deactivateAllPreviousBeacons(
            stores: SessionStores,
            roomId: String,
            userId: String,
            currentEventId: String,
            currentEventTimestamp: Long
    ) {
        stores.liveLocation.getRunningByRoom(roomId)
                .filter {
                    it.userId == userId &&
                            it.eventId != currentEventId &&
                            // Realm lessThan excludes null timestamps, so do we
                            (it.startOfLiveTimestampMillis?.let { ts -> ts < currentEventTimestamp } == true)
                }
                .forEach {
                    it.isActive = false
                    stores.liveLocation.upsert(it)
                }
    }

    private fun Long.isMoreRecentThan(timestamp: Long) = this > timestamp
}
