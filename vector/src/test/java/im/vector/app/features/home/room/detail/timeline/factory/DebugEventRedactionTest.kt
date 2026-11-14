/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.factory

import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.helper.TimelineEventVisibilityHelper
import im.vector.app.features.home.room.detail.timeline.item.DefaultItem
import im.vector.app.features.home.room.detail.timeline.item.MergedSimilarEventsItem
import im.vector.app.features.redaction.preservation.RedactedContentRestorer
import im.vector.lib.strings.CommonPlurals
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.crypto.model.OlmDecryptionResult
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DebugEventRedactionTest {

    private val debugTypes = EventType.POLL_RESPONSE.values + EventType.BEACON_LOCATION_DATA.values + listOf(
            EventType.CALL_CANDIDATES, EventType.KEY_VERIFICATION_KEY, EventType.REDACTION, EventType.REACTION, "com.example.debug",
    )
    private val visibility = mockk<TimelineEventVisibilityHelper> {
        every { shouldShowEvent(any(), any(), any(), any(), any()) } returns true
        every { isHiddenEvent(any(), any(), any()) } returns true
    }
    private val restorer = mockk<RedactedContentRestorer> {
        every { restore(any()) } answers { firstArg() }
        every { isShowingRestoredContent(any()) } returns false
    }

    private fun event(type: String, id: Long = 1L, redacted: Boolean = true, encrypted: Boolean = false): TimelineEvent {
        val eventId = "\$event$id"
        val root = Event(
                type = if (encrypted) EventType.ENCRYPTED else type,
                eventId = eventId,
                roomId = "!room:example.org",
                senderId = "@alice:example.org",
                content = emptyMap(),
                originServerTs = 1_700_000_000_000L,
                unsignedData = if (redacted) UnsignedData(age = null, redactedBy = "\$redaction") else null,
        ).apply {
            if (encrypted) mxDecryptionResult = OlmDecryptionResult(payload = mapOf("type" to type, "content" to emptyMap<String, Any>()))
        }
        return TimelineEvent(root = root, localId = id, eventId = eventId, senderInfo = SenderInfo("@alice:example.org", "Alice", true, null))
    }

    @Test
    fun `redacted debug types keep their debug models for plain and encrypted events`() {
        val debugItem = mockk<DefaultItem>()
        val defaultFactory = mockk<DefaultItemFactory> {
            every { create(any<TimelineItemFactoryParams>(), any()) } returns debugItem
        }
        val messageFactory = mockk<MessageItemFactory>()
        val factory = TimelineItemFactory(
                messageItemFactory = messageFactory,
                encryptedItemFactory = mockk(),
                noticeItemFactory = mockk(),
                defaultItemFactory = defaultFactory,
                encryptionItemFactory = mockk(),
                roomCreateItemFactory = mockk(),
                widgetItemFactory = mockk(),
                verificationConclusionItemFactory = mockk(),
                timelineEventVisibilityHelper = visibility,
                userPreferencesProvider = mockk(),
                redactedContentRestorer = restorer,
                sendingMediaGate = mockk(),
        )

        debugTypes.forEach { type ->
            listOf(false, true).forEach { encrypted ->
                factory.create(TimelineItemFactoryParams(event(type, encrypted = encrypted))) shouldBe debugItem
            }
        }
        verify(exactly = 0) { messageFactory.create(any()) }
    }

    @Test
    fun `redacted and intact debug events collapse together as hidden events`() {
        val factory = mergedFactory()
        val events = (1L..20L).map { id -> event(debugTypes[(id % debugTypes.size).toInt()], id, redacted = id % 2L == 0L) }
        factory.updateRuns(events.reversed(), TimelineEventController.PartialState(), emptySet())

        events.all { factory.isCollapsed(it.localId) } shouldBeEqualTo true
        val header = factory.create(events.first(), TimelineEventController.PartialState(), null, null, {}) as MergedSimilarEventsItem
        header.attributes.summaryTitleResId shouldBeEqualTo CommonPlurals.merged_hidden_events
    }

    @Test
    fun `ordinary deleted messages still collapse as redacted messages`() {
        every { visibility.isHiddenEvent(any(), any(), any()) } returns false
        val factory = mergedFactory()
        val events = (1L..10L).map { id -> event(EventType.MESSAGE, id) }
        factory.updateRuns(events.reversed(), TimelineEventController.PartialState(), emptySet())

        val header = factory.create(events.first(), TimelineEventController.PartialState(), null, null, {}) as MergedSimilarEventsItem
        header.attributes.summaryTitleResId shouldBeEqualTo CommonPlurals.room_redacted_messages
    }

    private fun mergedFactory() = MergedHeaderItemFactory(
            activeSessionHolder = mockk(relaxed = true),
            avatarRenderer = mockk(relaxed = true),
            avatarSizeProvider = mockk(relaxed = true),
            timelineEventVisibilityHelper = visibility,
            redactedContentRestorer = restorer,
    )
}
