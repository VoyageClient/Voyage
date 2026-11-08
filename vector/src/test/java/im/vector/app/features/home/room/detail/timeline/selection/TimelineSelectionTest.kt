/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.selection

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent

class TimelineSelectionTest {

    private val selection = TimelineSelection()

    @Test
    fun `selected events come out in timeline order, not tap order`() {
        val first = anEvent("\$a", ts = 1_000)
        val tieEarlier = anEvent("\$b", ts = 2_000, seq = 1)
        val tieLater = anEvent("\$c", ts = 2_000, seq = 2)
        listOf(tieLater, first, tieEarlier).forEach { selection.toggle(it) }

        TimelineSelection.inTimelineOrder(selection.selected.value.values).map { it.eventId } shouldBeEqualTo listOf("\$a", "\$b", "\$c")
    }

    @Test
    fun `toggling twice deselects and an empty selection ends the mode`() {
        val event = anEvent("\$a")
        selection.toggle(event)
        selection.isActive shouldBeEqualTo true
        selection.toggle(event)
        selection.isActive shouldBeEqualTo false
    }

    @Test
    fun `a drag range is applied on top of the selection it started from`() {
        val kept = anEvent("\$kept")
        selection.toggle(kept)
        val base = selection.selected.value
        val a = anEvent("\$a")
        val b = anEvent("\$b")

        selection.applyRange(base, listOf(a, b), select = true)
        selection.selected.value.keys shouldBeEqualTo setOf("\$kept", "\$a", "\$b")

        // Dragging back over b returns it to how it was before the drag.
        selection.applyRange(base, listOf(a), select = true)
        selection.selected.value.keys shouldBeEqualTo setOf("\$kept", "\$a")
    }

    @Test
    fun `a deselecting drag clears what it passes over and restores what it leaves`() {
        val a = anEvent("\$a")
        val b = anEvent("\$b")
        val c = anEvent("\$c")
        listOf(a, b, c).forEach { selection.toggle(it) }
        val base = selection.selected.value

        selection.applyRange(base, listOf(a, b), select = false)
        selection.selected.value.keys shouldBeEqualTo setOf("\$c")

        selection.applyRange(base, listOf(a), select = false)
        selection.selected.value.keys shouldBeEqualTo setOf("\$b", "\$c")
    }

    @Test
    fun `refresh drops events that were redacted meanwhile`() {
        selection.toggle(anEvent("\$a"))
        selection.toggle(anEvent("\$b"))

        selection.refresh { id -> if (id == "\$a") anEvent("\$a", redacted = true) else null }

        selection.selected.value.keys shouldBeEqualTo setOf("\$b")
    }

    @Test
    fun `unsent and non-message events are not selectable`() {
        TimelineSelection.isSelectable(anEvent("\$a", sendState = SendState.SENDING)) shouldBeEqualTo false
        TimelineSelection.isSelectable(anEvent("\$b", type = EventType.STATE_ROOM_NAME)) shouldBeEqualTo false
        TimelineSelection.isSelectable(anEvent("\$poll", type = EventType.POLL_START.unstable)) shouldBeEqualTo false
        TimelineSelection.isSelectable(anEvent("\$c")) shouldBeEqualTo true
        TimelineSelection.isSelectable(anEvent("\$sticker", type = EventType.STICKER)) shouldBeEqualTo true
    }

    private fun anEvent(
            eventId: String,
            ts: Long = 0,
            seq: Long = 0,
            type: String = EventType.MESSAGE,
            sendState: SendState = SendState.SYNCED,
            redacted: Boolean = false,
    ) = TimelineEvent(
            root = Event(
                    type = type,
                    eventId = eventId,
                    roomId = "!room:example.org",
                    senderId = "@alice:example.org",
                    originServerTs = ts,
                    content = mapOf("msgtype" to "m.text", "body" to eventId),
                    unsignedData = if (redacted) UnsignedData(age = null, redactedEvent = Event(eventId = "\$redaction")) else null,
            ).also { it.sendState = sendState },
            localId = 0,
            eventId = eventId,
            senderInfo = SenderInfo("@alice:example.org", "alice", true, null),
            seq = seq,
    )
}
