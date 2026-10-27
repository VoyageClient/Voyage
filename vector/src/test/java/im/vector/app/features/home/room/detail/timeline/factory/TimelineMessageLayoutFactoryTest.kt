/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.factory

import im.vector.app.core.resources.LocaleProvider
import im.vector.app.features.home.room.detail.timeline.style.TimelineLayoutSettings
import im.vector.app.features.home.room.detail.timeline.style.TimelineLayoutSettingsProvider
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayoutFactory
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.BubbleThemeUtils
import im.vector.app.features.themes.defaultScBubbleAppearance
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TimelineMessageLayoutFactoryTest {

    private val preferences = mockk<VectorPreferences>(relaxed = true) {
        every { showMessageTimestamps() } returns true
    }
    private val settingsProvider = mockk<TimelineLayoutSettingsProvider>()
    private val bubbleThemeUtils = mockk<BubbleThemeUtils>(relaxed = true) {
        every { getBubbleAppearance() } returns defaultScBubbleAppearance
    }
    private val factory = TimelineMessageLayoutFactory(
            session = mockk<Session> { every { myUserId } returns "@me:example.org" },
            layoutSettingsProvider = settingsProvider,
            localeProvider = mockk<LocaleProvider> { every { current() } returns Locale.US },
            resources = mockk(relaxed = true),
            bubbleThemeUtils = bubbleThemeUtils,
            vectorPreferences = preferences,
            lightweightSettingsStorage = mockk(relaxed = true),
    )

    @Test
    fun `hiding timestamps preserves sender grouping in every layout`() {
        forEachLayout { _, _ ->
            listOf(MessageType.MSGTYPE_TEXT, MessageType.MSGTYPE_EMOTE, MessageType.MSGTYPE_LOCATION).forEach { messageType ->
                listOf(false, true).forEach { grouped ->
                    val params = TimelineItemFactoryParams(
                            event = event(messageType),
                            nextDisplayableEvent = if (grouped) event(messageType) else null,
                    )
                    every { preferences.showMessageTimestamps() } returns true
                    val visible = factory.create(params)
                    every { preferences.showMessageTimestamps() } returns false
                    val hidden = factory.create(params)

                    hidden.showTimestamp shouldBeEqualTo false
                    hidden.showDisplayName shouldBeEqualTo visible.showDisplayName
                    hidden.showAvatar shouldBeEqualTo visible.showAvatar
                }
            }
        }
    }

    @Test
    fun `enabled timestamps keep the normal grouping rules`() {
        forEachLayout { layout, singleSided ->
            every { preferences.showMessageTimestamps() } returns true
            factory.create(TimelineItemFactoryParams(event = event())).showTimestamp shouldBeEqualTo true
            val grouped = factory.create(TimelineItemFactoryParams(event = event(), nextDisplayableEvent = event()))

            grouped.showTimestamp shouldBeEqualTo (layout == TimelineLayoutSettings.BUBBLE ||
                    (layout == TimelineLayoutSettings.SC_BUBBLE && !singleSided))
        }
    }

    @Test
    fun `fallback tiles and dummy layouts respect hidden timestamps`() {
        every { preferences.showMessageTimestamps() } returns false
        forEachLayout { _, _ ->
            factory.create(TimelineItemFactoryParams(event = event(MessageType.MSGTYPE_VERIFICATION_REQUEST)))
                    .showTimestamp shouldBeEqualTo false
            factory.createDummy().showTimestamp shouldBeEqualTo false
        }
    }

    private fun forEachLayout(block: (TimelineLayoutSettings, Boolean) -> Unit) {
        TimelineLayoutSettings.values().forEach { layout ->
            every { settingsProvider.getLayoutSettings() } returns layout
            listOf(false, true).forEach { singleSided ->
                every { bubbleThemeUtils.getBubbleStyle() } returns if (singleSided) {
                    BubbleThemeUtils.BUBBLE_STYLE_START
                } else {
                    BubbleThemeUtils.BUBBLE_STYLE_BOTH
                }
                block(layout, singleSided)
            }
        }
    }

    private fun event(messageType: String = MessageType.MSGTYPE_TEXT) = TimelineEvent(
            root = Event(
                    eventId = "event",
                    type = EventType.MESSAGE,
                    senderId = "@alice:example.org",
                    originServerTs = 1_700_000_000_000L,
                    content = mapOf(
                            "msgtype" to messageType,
                            "body" to "Hello",
                            "geo_uri" to "geo:0,0",
                            "from_device" to "DEVICE",
                            "methods" to listOf("m.sas.v1"),
                            "to" to "@me:example.org",
                    ),
            ),
            localId = 1L,
            eventId = "event",
            senderInfo = mockk(relaxed = true),
    )
}
