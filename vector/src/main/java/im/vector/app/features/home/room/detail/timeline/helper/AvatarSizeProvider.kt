/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.home.room.detail.timeline.item.E2EDecoration
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.home.room.detail.timeline.style.TimelineLayoutSettings
import im.vector.app.features.home.room.detail.timeline.style.TimelineLayoutSettingsProvider
import im.vector.app.features.themes.BubbleThemeUtils
import javax.inject.Inject

class AvatarSizeProvider @Inject constructor(
        private val dimensionConverter: DimensionConverter,
        private val layoutSettingsProvider: TimelineLayoutSettingsProvider,
        private val bubbleThemeUtils: BubbleThemeUtils,
) {

    private val avatarStyle by lazy {
        when (layoutSettingsProvider.getLayoutSettings()) {
            TimelineLayoutSettings.MODERN -> AvatarStyle.SMALL
            TimelineLayoutSettings.BUBBLE -> AvatarStyle.BUBBLE
            TimelineLayoutSettings.SC_BUBBLE -> AvatarStyle.SMALL
        }
    }

    val leftGuideline: Int by lazy {
        dimensionConverter.dpToPx(avatarStyle.avatarSizeDP + avatarStyle.marginDP)
    }

    // Two-sided bubbles in a DM hide both avatars, so non-message rows align with the bubbles instead.
    fun nonMessageGuideline(isDirect: Boolean): Int {
        val bubblesWithoutAvatars = isDirect && bubbleThemeUtils.getBubbleStyle() == BubbleThemeUtils.BUBBLE_STYLE_BOTH
        return if (bubblesWithoutAvatars) {
            dimensionConverter.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_bubble_tail_size)
        } else {
            leftGuideline
        }
    }

    // An E2E shield sits in the guideline's gutter and needs its full width.
    fun nonMessageGuideline(informationData: MessageInformationData): Int {
        return if (informationData.e2eDecoration == E2EDecoration.NONE) nonMessageGuideline(informationData.isDirect) else leftGuideline
    }

    val avatarSize: Int by lazy {
        dimensionConverter.dpToPx(avatarStyle.avatarSizeDP)
    }

    companion object {

        enum class AvatarStyle(val avatarSizeDP: Int, val marginDP: Int) {
            BIG(50, 8),
            MEDIUM(40, 8),
            SMALL(30, 8),
            BUBBLE(28, 4),
            NONE(0, 8)
        }
    }
}
