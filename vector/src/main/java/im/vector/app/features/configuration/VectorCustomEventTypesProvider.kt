/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.configuration

import im.vector.app.features.home.room.detail.timeline.STATE_ROOM_VOICE_BROADCAST_INFO
import org.matrix.android.sdk.api.provider.CustomEventTypesProvider
import javax.inject.Inject

class VectorCustomEventTypesProvider @Inject constructor() : CustomEventTypesProvider {

    // Voice broadcast playback/recording is removed on this fork, but keep the state event previewable
    // so it can still be surfaced as a timeline notice.
    override val customPreviewableEventTypes = listOf(
            STATE_ROOM_VOICE_BROADCAST_INFO
    )
}
