/*
 * Copyright (c) 2023 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.room.poll

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.room.poll.LoadedPollsStatus
import org.matrix.android.sdk.internal.database.model.PollHistoryStatusEntity
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.robolectric.RobolectricTestRunner

private const val A_ROOM_ID = "room-id"

/**
 * Timestamp in milliseconds corresponding to 2023/01/26.
 */
private const val A_CURRENT_TIMESTAMP = 1674737619290L

/**
 * Timestamp in milliseconds corresponding to 2023/01/20.
 */
private const val AN_EVENT_TIMESTAMP = 1674169200000L

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
internal class DefaultGetLoadedPollsStatusTaskTest {

    private val db = FakeSessionDatabase()

    private val defaultGetLoadedPollsStatusTask = DefaultGetLoadedPollsStatusTask(
            database = db.database,
            dispatcher = db.dispatcher,
            stores = db.stores,
    )

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `given poll history status exists in db with an oldestTimestamp reached when execute then the computed status is returned`() = runTest {
        // Given
        val params = givenTaskParams()
        db.stores.pollHistory.upsert(aPollHistoryStatusEntity(
                isEndOfPollsBackward = false,
                oldestTimestampReached = AN_EVENT_TIMESTAMP,
        ))
        val expectedStatus = LoadedPollsStatus(
                canLoadMore = true,
                daysSynced = 6,
                hasCompletedASyncBackward = true,
        )

        // When
        val result = defaultGetLoadedPollsStatusTask.execute(params)

        // Then
        result shouldBeEqualTo expectedStatus
    }

    @Test
    fun `given poll history status exists in db and no oldestTimestamp reached when execute then the computed status is returned`() = runTest {
        // Given
        val params = givenTaskParams()
        db.stores.pollHistory.upsert(aPollHistoryStatusEntity(
                isEndOfPollsBackward = false,
                oldestTimestampReached = null,
        ))
        val expectedStatus = LoadedPollsStatus(
                canLoadMore = true,
                daysSynced = 0,
                hasCompletedASyncBackward = false,
        )

        // When
        val result = defaultGetLoadedPollsStatusTask.execute(params)

        // Then
        result shouldBeEqualTo expectedStatus
    }

    private fun givenTaskParams(): GetLoadedPollsStatusTask.Params {
        return GetLoadedPollsStatusTask.Params(
                roomId = A_ROOM_ID,
                currentTimestampMs = A_CURRENT_TIMESTAMP,
        )
    }

    private fun aPollHistoryStatusEntity(
            isEndOfPollsBackward: Boolean,
            oldestTimestampReached: Long?,
    ): PollHistoryStatusEntity {
        return PollHistoryStatusEntity(
                roomId = A_ROOM_ID,
                isEndOfPollsBackward = isEndOfPollsBackward,
                oldestTimestampTargetReachedMs = oldestTimestampReached,
        )
    }
}
