/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.factory

import im.vector.app.features.home.room.detail.timeline.helper.TimelineSettingsFactory
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import javax.inject.Inject

class TimelineFactory @Inject constructor(private val timelineSettingsFactory: TimelineSettingsFactory) {

    fun createTimeline(
            mainRoom: Room,
            eventId: String?,
            rootThreadEventId: String?
    ): Timeline {
        val settings = timelineSettingsFactory.create(rootThreadEventId)
        return mainRoom.timelineService().createTimeline(eventId, settings)
    }
}
