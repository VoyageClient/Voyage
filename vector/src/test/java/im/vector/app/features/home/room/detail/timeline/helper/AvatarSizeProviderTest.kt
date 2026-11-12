/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.home.room.detail.timeline.item.E2EDecoration
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.home.room.detail.timeline.style.TimelineLayoutSettingsProvider
import im.vector.app.features.themes.BubbleThemeUtils
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AvatarSizeProviderTest {
    private val resources = RuntimeEnvironment.getApplication().resources
    private val tail = resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_bubble_tail_size)

    private fun provider(style: String): AvatarSizeProvider {
        val bubbleThemeUtils = mockk<BubbleThemeUtils> { every { getBubbleStyle() } returns style }
        return AvatarSizeProvider(DimensionConverter(resources), TimelineLayoutSettingsProvider(bubbleThemeUtils), bubbleThemeUtils)
    }

    private fun informationData(isDirect: Boolean, decoration: E2EDecoration) = mockk<MessageInformationData> {
        every { this@mockk.isDirect } returns isDirect
        every { e2eDecoration } returns decoration
    }

    @Test
    fun `two-sided bubbles in a DM align rows with the bubbles`() {
        assertEquals(tail, provider(BubbleThemeUtils.BUBBLE_STYLE_BOTH).nonMessageGuideline(isDirect = true))
    }

    @Test
    fun `rows keep the avatar column outside DMs and other layouts`() {
        val both = provider(BubbleThemeUtils.BUBBLE_STYLE_BOTH)
        assertEquals(both.leftGuideline, both.nonMessageGuideline(isDirect = false))
        listOf(BubbleThemeUtils.BUBBLE_STYLE_START, BubbleThemeUtils.BUBBLE_STYLE_NONE, BubbleThemeUtils.BUBBLE_STYLE_ELEMENT).forEach {
            val other = provider(it)
            assertEquals(it, other.leftGuideline, other.nonMessageGuideline(isDirect = true))
        }
    }

    @Test
    fun `a row with an E2E shield keeps room for it`() {
        val provider = provider(BubbleThemeUtils.BUBBLE_STYLE_BOTH)
        assertEquals(tail, provider.nonMessageGuideline(informationData(true, E2EDecoration.NONE)))
        assertEquals(provider.leftGuideline, provider.nonMessageGuideline(informationData(true, E2EDecoration.WARN_SENT_BY_UNVERIFIED)))
    }
}
