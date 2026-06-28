/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import im.vector.app.core.resources.UserPreferencesProvider
import org.matrix.android.sdk.api.session.room.timeline.TimelineSettings
import javax.inject.Inject

class TimelineSettingsFactory @Inject constructor(private val userPreferencesProvider: UserPreferencesProvider) {

    fun create(rootThreadEventId: String?): TimelineSettings {
        return TimelineSettings(
                // Each formatted message costs ~100ms to render on a slow ICS device; a smaller initial
                // page populates the room faster and the rest paginates in on scroll.
                initialSize = 15,
                buildReadReceipts = userPreferencesProvider.shouldShowReadReceipts(),
                rootThreadEventId = rootThreadEventId,
                useLiveSenderInfo = userPreferencesProvider.showLiveSenderInfo()
        )
    }
}
