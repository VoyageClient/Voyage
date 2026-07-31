/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.test.fakes

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.TimelineService
import org.matrix.android.sdk.api.util.Optional

class FakeTimelineService : TimelineService by mockk() {

    fun givenTimelineEventReturns(eventId: String, event: TimelineEvent?) {
        every { getTimelineEvent(eventId) } returns event
    }

    fun givenTimelineEventLiveReturns(
            eventId: String,
            event: TimelineEvent?
    ): Flow<Optional<TimelineEvent>> {
        return flowOf(Optional(event)).also {
            every { getTimelineEventFlow(eventId) } returns it
        }
    }
}
