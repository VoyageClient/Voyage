/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.factory

import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData

data class TimelineItemPresentation(
        val informationData: MessageInformationData,
        val noticeText: CharSequence? = null,
)
