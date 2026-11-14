/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.factory

import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.helper.TimelineEventVisibilityHelper
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val A_ROOM_ID = "!room:example.org"
private const val CREATOR = "@alice:example.org"
private const val SOMEONE_ELSE = "@bob:example.org"
private const val A_TIMESTAMP = 1_700_000_000_000L
private const val A_DAY = 24 * 60 * 60 * 1000L

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MergedHeaderRoomCreationTest {

    private val visibilityHelper = mockk<TimelineEventVisibilityHelper> {
        every { shouldShowEvent(any(), any(), any(), any(), any()) } returns true
        every { isHiddenEvent(any(), any(), any()) } returns false
    }

    private val factory = MergedHeaderItemFactory(
            activeSessionHolder = mockk(relaxed = true),
            avatarRenderer = mockk(relaxed = true),
            avatarSizeProvider = mockk(relaxed = true),
            timelineEventVisibilityHelper = visibilityHelper,
            redactedContentRestorer = mockk(relaxed = true),
    )

    private var nextLocalId = 1L

    private fun event(
            type: String,
            stateKey: String? = "",
            sender: String = CREATOR,
            content: Content = emptyMap(),
            ts: Long = A_TIMESTAMP,
    ): TimelineEvent {
        val localId = nextLocalId++
        val eventId = "\$event$localId"
        return TimelineEvent(
                root = Event(type = type, eventId = eventId, roomId = A_ROOM_ID, senderId = sender, stateKey = stateKey, content = content, originServerTs = ts),
                localId = localId,
                eventId = eventId,
                senderInfo = SenderInfo(sender, null, false, null),
        )
    }

    private fun join(userId: String) = event(EventType.STATE_ROOM_MEMBER, stateKey = userId, sender = userId, content = mapOf("membership" to "join"))

    // Built oldest first, handed to the factory newest first like a timeline snapshot.
    private fun runOf(vararg oldestFirst: TimelineEvent): List<TimelineEvent> {
        factory.updateRuns(oldestFirst.reversed(), TimelineEventController.PartialState(), emptySet())
        return oldestFirst.filter { factory.isCollapsed(it.localId) }
    }

    @Test
    fun `given a v11 room creation, then the create event and all creator setup collapse into one group`() {
        val create = event(EventType.STATE_ROOM_CREATE, content = mapOf("room_version" to "11"))
        val setup = listOf(
                join(CREATOR),
                event(EventType.STATE_ROOM_POWER_LEVELS),
                event(EventType.STATE_ROOM_JOIN_RULES),
                event(EventType.STATE_ROOM_HISTORY_VISIBILITY),
                event(EventType.STATE_ROOM_GUEST_ACCESS),
                event(EventType.STATE_ROOM_NAME),
        )
        val message = event(EventType.MESSAGE, stateKey = null)

        runOf(create, *setup.toTypedArray(), message) shouldBeEqualTo listOf(create) + setup
        factory.isMergedAnchor(create.localId) shouldBeEqualTo true
    }

    @Test
    fun `given someone else joins, then the group ends before them`() {
        val create = event(EventType.STATE_ROOM_CREATE)
        val creatorJoin = join(CREATOR)
        val powerLevels = event(EventType.STATE_ROOM_POWER_LEVELS)
        val otherJoin = join(SOMEONE_ELSE)
        val laterName = event(EventType.STATE_ROOM_NAME)

        runOf(create, creatorJoin, powerLevels, otherJoin, laterName) shouldBeEqualTo listOf(create, creatorJoin, powerLevels)
    }

    @Test
    fun `given creator state on a later day, then it is not part of the group`() {
        val create = event(EventType.STATE_ROOM_CREATE)
        val creatorJoin = join(CREATOR)
        val nextDayTopic = event(EventType.STATE_ROOM_TOPIC, ts = A_TIMESTAMP + A_DAY)

        runOf(create, creatorJoin, nextDayTopic) shouldBeEqualTo listOf(create, creatorJoin)
    }

    @Test
    fun `given an upgraded room, then its create event stays outside the group`() {
        val create = event(EventType.STATE_ROOM_CREATE, content = mapOf("predecessor" to mapOf("room_id" to "!old:example.org")))
        val creatorJoin = join(CREATOR)
        val powerLevels = event(EventType.STATE_ROOM_POWER_LEVELS)

        runOf(create, creatorJoin, powerLevels) shouldBeEqualTo listOf(creatorJoin, powerLevels)
        factory.isMergedAnchor(creatorJoin.localId) shouldBeEqualTo true
    }

    @Test
    fun `given nothing follows the create event, then there is no group`() {
        val create = event(EventType.STATE_ROOM_CREATE)
        val message = event(EventType.MESSAGE, stateKey = null)

        runOf(create, message) shouldBeEqualTo emptyList()
    }
}
